import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createRuntime, trimContext } from './worker.mjs';

const wait = async (check, timeout = 5000) => {
  const end = Date.now() + timeout;
  while (!check()) {
    if (Date.now() > end) throw new Error('timeout');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
};
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const count = (text, needle) => text.split(needle).length - 1;
const TOOL_SCHEMA = { type: 'object', properties: { query: { type: 'string' } }, required: ['query'], additionalProperties: false };
const TOOL_SCHEMA_V2 = { type: 'object', properties: { query: { type: 'string' }, limit: { type: 'integer' } }, required: ['query'], additionalProperties: false };
const start = (id, session, overrides = {}) => ({
  id,
  op: 'start',
  session,
  message: 'question',
  history: [],
  systemPrompt: 'private system',
  tools: [{ name: 'lookup', description: 'Look up facts', parameters: TOOL_SCHEMA }],
  toolsetId: `toolset-${id}`,
  environment: 'ENV_BASE',
  ...overrides
});
const providerEnv = url => ({ FARMAP_PI_API_KEY: 'secret-key', FARMAP_PI_BASE_URL: url, FARMAP_PI_MODEL: 'mock' });
const toolRequests = outputs => outputs.filter(value => value.type === 'tool_call_request');
const reply = (runtime, call, data, isError = false) => runtime.handle({
  op: 'tool_call_response',
  id: call.id,
  callId: call.callId,
  text: typeof data === 'string' ? data : JSON.stringify(data),
  isError
});

async function mockProvider(t, respond) {
  const requests = [];
  const headersList = [];
  const server = http.createServer(async (request, response) => {
    let raw = '';
    for await (const chunk of request) raw += chunk;
    const body = JSON.parse(raw);
    requests.push(body);
    headersList.push(request.headers);
    response.writeHead(200, { 'content-type': 'text/event-stream' });
    const chunk = (delta, finishReason = null) => response.write(`data: ${JSON.stringify({ id: 'a', object: 'chat.completion.chunk', created: 1, model: 'mock', choices: [{ index: 0, delta, finish_reason: finishReason }] })}\n\n`);
    const answer = text => { chunk({ content: text }); chunk({}, 'stop'); response.end('data: [DONE]\n\n'); };
    const toolCalls = calls => {
      chunk({ tool_calls: calls.map((call, index) => ({ index, id: call.id, type: 'function', function: { name: call.name, arguments: JSON.stringify(call.args ?? {}) } })) });
      chunk({}, 'tool_calls');
      response.end('data: [DONE]\n\n');
    };
    await respond({ index: requests.length, body, chunk, answer, toolCalls, response });
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  t.after(() => { server.closeAllConnections(); server.close(); });
  return { requests, headers: headersList, url: `http://127.0.0.1:${server.address().port}/v1` };
}

test('live tool RPC forwards validated args, returns Java JSON natively, and ignores bad correlation', async t => {
  const provider = await mockProvider(t, ({ index, toolCalls, answer }) => {
    if (index === 1) toolCalls([{ id: 'provider-call-1', name: 'lookup', args: { query: 'field-9' } }]);
    else answer('answer');
  });
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('one', 'owner-a', { environment: 'LIVE_ENV' }));
  await wait(() => toolRequests(outputs).length === 1);
  const call = toolRequests(outputs)[0];
  assert.equal(call.id, 'one');
  assert.equal(call.type, 'tool_call_request');
  assert.equal(call.toolsetId, 'toolset-one');
  assert.equal(call.toolName, 'lookup');
  assert.deepEqual(call.args, { query: 'field-9' });
  assert.match(call.callId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assert.notEqual(call.callId, 'provider-call-1');

  runtime.handle({ op: 'tool_call_response', id: 'other-command', callId: call.callId, text: '{"value":"wrong-command"}', isError: false });
  runtime.handle({ op: 'tool_call_response', id: 'one', callId: 'wrong-call', text: '{"value":"wrong-call"}', isError: false });
  await delay(30);
  assert.ok(!outputs.some(value => value.id === 'one' && value.type === 'done'));
  assert.ok(!JSON.stringify(outputs).includes('wrong-command'));
  assert.equal(runtime.pending.size, 1);

  reply(runtime, call, { value: 'live-return' });
  await wait(() => outputs.some(value => value.id === 'one' && value.type === 'done'));
  assert.equal(outputs.find(value => value.id === 'one' && value.type === 'done').text, 'answer');
  assert.equal(runtime.pending.size, 0);
  assert.equal(provider.requests.length, 2);
  assert.ok(provider.requests[1].messages.some(message => message.role === 'tool' && JSON.stringify(message).includes('live-return')));
  assert.equal(count(JSON.stringify(provider.requests[0]), 'LIVE_ENV'), 1);
  assert.ok(!JSON.stringify(outputs).includes('live-return'));
  assert.ok(!JSON.stringify(outputs).includes('LIVE_ENV'));
  const end = outputs.find(value => value.event?.type === 'tool_execution_end')?.event;
  assert.ok(end);
  assert.ok(!('result' in end));
  assert.ok(!('args' in end));
});

test('Java tool errors stay native and do not leak response data into events', async t => {
  const provider = await mockProvider(t, ({ index, toolCalls, answer }) => {
    if (index === 1) toolCalls([{ id: 'provider-error', name: 'lookup', args: { query: 'denied' } }]);
    else answer('handled');
  });
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('error', 'owner-error'));
  await wait(() => toolRequests(outputs).length === 1);
  reply(runtime, toolRequests(outputs)[0], { status: 'TOOL_DENIED' }, true);
  await wait(() => outputs.some(value => value.id === 'error' && value.type === 'done'));
  assert.equal(outputs.find(value => value.id === 'error' && value.type === 'done').text, 'handled');
  assert.equal(outputs.find(value => value.event?.type === 'tool_execution_end')?.event.isError, true);
  assert.ok(JSON.stringify(provider.requests[1]).includes('TOOL_DENIED'));
  assert.ok(!JSON.stringify(outputs).includes('TOOL_DENIED'));
});

test('same scope reuses native transcript while refreshing environment and toolset without duplicates', async t => {
  const provider = await mockProvider(t, ({ index, toolCalls, answer }) => {
    if (index === 1) toolCalls([{ id: 'provider-old', name: 'lookup', args: { query: 'old' } }]);
    else if (index === 2) answer('answer-one');
    else if (index === 3) toolCalls([{ id: 'provider-new', name: 'lookup', args: { query: 'new' } }]);
    else answer('answer-two');
  });
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('one', 'stable', { contextVersion: 1, scope: 'field-a', environment: 'ENV_ONE' }));
  await wait(() => toolRequests(outputs).length === 1);
  reply(runtime, toolRequests(outputs)[0], { value: 'first-native' });
  await wait(() => outputs.some(value => value.id === 'one' && value.type === 'done'));

  runtime.handle(start('two', 'stable', {
    contextVersion: 2,
    scope: 'field-a',
    message: 'next question',
    history: [{ role: 'user', text: 'question' }, { role: 'assistant', text: 'answer-one' }],
    tools: [{ name: 'lookup', description: 'Lookup v2', parameters: TOOL_SCHEMA_V2 }],
    toolsetId: 'toolset-two',
    environment: 'ENV_TWO'
  }));
  await wait(() => toolRequests(outputs).length === 2);
  const secondCall = toolRequests(outputs)[1];
  assert.equal(secondCall.id, 'two');
  assert.equal(secondCall.toolsetId, 'toolset-two');
  assert.deepEqual(secondCall.args, { query: 'new' });

  const reused = JSON.stringify(provider.requests[2]);
  assert.equal(count(reused, 'ENV_TWO'), 1);
  assert.equal(count(reused, 'ENV_ONE'), 0);
  assert.equal(count(reused, 'first-native'), 1);
  assert.equal(provider.requests[2].messages.filter(message => message.role === 'tool').length, 1);
  assert.equal(provider.requests[2].tools.find(tool => tool.function?.name === 'lookup')?.function.description, 'Lookup v2');
  assert.equal(runtime.sessions.get('stable').environment, 'ENV_TWO');
  assert.equal(runtime.sessions.get('stable').toolsetId, 'toolset-two');

  reply(runtime, secondCall, { value: 'second-native' });
  await wait(() => outputs.some(value => value.id === 'two' && value.type === 'done'));
  assert.equal(outputs.find(value => value.id === 'two' && value.type === 'done').text, 'answer-two');
  assert.ok(!JSON.stringify(outputs).includes('first-native'));
  assert.ok(!JSON.stringify(outputs).includes('second-native'));
});

test('parallel tool calls are emitted before replies and complete with both native results', async t => {
  const provider = await mockProvider(t, ({ index, toolCalls, answer }) => {
    if (index === 1) toolCalls([
      { id: 'provider-alpha', name: 'alpha', args: { value: 1 } },
      { id: 'provider-beta', name: 'beta', args: { value: 2 } }
    ]);
    else answer('parallel-answer');
  });
  const schema = { type: 'object', properties: { value: { type: 'integer' } }, required: ['value'], additionalProperties: false };
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('parallel', 'owner-parallel', {
    tools: [
      { name: 'alpha', description: 'Alpha', parameters: schema },
      { name: 'beta', description: 'Beta', parameters: schema }
    ],
    toolsetId: 'parallel-set',
    environment: 'PARALLEL_ENV'
  }));
  await wait(() => toolRequests(outputs).length === 2);
  const calls = toolRequests(outputs);
  assert.deepEqual(calls.map(call => call.toolName).sort(), ['alpha', 'beta']);
  assert.equal(new Set(calls.map(call => call.callId)).size, 2);
  assert.ok(calls.every(call => call.id === 'parallel' && call.toolsetId === 'parallel-set'));
  assert.equal(runtime.pending.size, 2);

  const beta = calls.find(call => call.toolName === 'beta');
  const alpha = calls.find(call => call.toolName === 'alpha');
  reply(runtime, beta, { value: 'beta-return' });
  reply(runtime, alpha, { value: 'alpha-return' });
  await wait(() => outputs.some(value => value.id === 'parallel' && value.type === 'done'));
  assert.equal(outputs.find(value => value.id === 'parallel' && value.type === 'done').text, 'parallel-answer');
  assert.equal(runtime.pending.size, 0);
  assert.equal(provider.requests[1].messages.filter(message => message.role === 'tool').length, 2);
  assert.ok(JSON.stringify(provider.requests[1]).includes('alpha-return'));
  assert.ok(JSON.stringify(provider.requests[1]).includes('beta-return'));
  assert.ok(!JSON.stringify(outputs).includes('alpha-return'));
  assert.ok(!JSON.stringify(outputs).includes('beta-return'));
});

test('abort releases a pending tool call, ignores its late response, and still allows continue', async t => {
  const provider = await mockProvider(t, ({ index, toolCalls, answer }) => {
    if (index === 1) toolCalls([{ id: 'provider-pending', name: 'lookup', args: { query: 'wait' } }]);
    else if (index === 2) toolCalls([{ id: 'provider-resumed', name: 'lookup', args: { query: 'resume' } }]);
    else answer('resumed');
  });
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('start', 's', { contextVersion: 1, scope: 'field-a' }));
  await wait(() => toolRequests(outputs).length === 1);
  const call = toolRequests(outputs)[0];
  assert.equal(runtime.pending.size, 1);

  runtime.handle({ id: 'stop', op: 'abort', session: 's', contextVersion: 1 });
  await wait(() => outputs.some(value => value.id === 'start' && value.type === 'done'));
  assert.equal(outputs.find(value => value.id === 'start' && value.type === 'done').status, 'aborted');
  assert.equal(runtime.pending.size, 0);

  const beforeLate = outputs.length;
  runtime.handle({ op: 'tool_call_response', id: 'start', callId: call.callId, text: '{"value":"late-secret"}', isError: false });
  await delay(30);
  assert.equal(outputs.length, beforeLate);
  assert.ok(!JSON.stringify(outputs).includes('late-secret'));

  runtime.handle({ id: 'continue', op: 'continue', session: 's', contextVersion: 1 });
  await wait(() => toolRequests(outputs).length === 2);
  const resumedCall = toolRequests(outputs)[1];
  assert.equal(resumedCall.id, 'continue');
  assert.equal(resumedCall.toolsetId, 'toolset-start');
  assert.deepEqual(resumedCall.args, { query: 'resume' });
  reply(runtime, resumedCall, { value: 'resumed-return' });
  await wait(() => outputs.some(value => value.id === 'continue' && ['done', 'error'].includes(value.type)));
  const continued = outputs.find(value => value.id === 'continue' && ['done', 'error'].includes(value.type));
  assert.equal(continued.type, 'done');
  assert.equal(continued.status, 'completed');
  assert.equal(continued.text, 'resumed');
  assert.equal(provider.requests.length, 3);
});

test('abort, continue, steer, and follow-up keep the native live agent state', async t => {
  const provider = await mockProvider(t, async ({ index, response, chunk }) => {
    if (index === 1) {
      response.write(': waiting\n\n');
      return;
    }
    chunk({ content: 'resumed' });
    chunk({}, 'stop');
    response.end('data: [DONE]\n\n');
  });
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('a', 'control'));
  await wait(() => provider.requests.length === 1);
  runtime.handle({ id: 'steer', op: 'steer', session: 'control', message: 'steer now' });
  runtime.handle({ id: 'queue', op: 'followUp', session: 'control', message: 'next' });
  assert.equal(outputs.find(value => value.id === 'steer').type, 'done');
  assert.equal(outputs.find(value => value.id === 'queue').type, 'done');
  runtime.handle({ id: 'abort', op: 'abort', session: 'control' });
  await wait(() => outputs.some(value => value.id === 'a' && value.type === 'done'));
  assert.equal(outputs.find(value => value.id === 'a' && value.type === 'done').status, 'aborted');
  runtime.handle({ id: 'c', op: 'continue', session: 'control' });
  await wait(() => outputs.some(value => value.id === 'c' && ['done', 'error'].includes(value.type)));
  assert.equal(outputs.find(value => value.id === 'c' && ['done', 'error'].includes(value.type)).type, 'done');
  assert.ok(provider.requests.length >= 2);
});

test('OpenCode keeps compatible model settings and receives native tool feedback', async t => {
  const provider = await mockProvider(t, ({ index, toolCalls, answer }) => {
    if (index === 1) toolCalls([{ id: 'provider-open', name: 'lookup', args: { query: 'safe' } }]);
    else answer('answer');
  });
  const outputs = [];
  const runtime = createRuntime({
    emit: value => outputs.push(value),
    env: {
      FARMAP_OPENCODE_ENABLED: 'true',
      FARMAP_OPENCODE_API_KEY: 'oc-secret',
      FARMAP_OPENCODE_URL: `${provider.url}/chat/completions`,
      FARMAP_PI_MODEL: 'legacy',
      FARMAP_PI_API_KEY: 'legacy-secret'
    }
  });
  runtime.handle(start('one', 'private-user@example.com', { environment: 'private environment' }));
  await wait(() => toolRequests(outputs).length === 1);
  reply(runtime, toolRequests(outputs)[0], { value: 'opencode-return' });
  await wait(() => outputs.some(value => value.id === 'one' && value.type === 'done'));
  assert.equal(outputs.find(value => value.id === 'one' && value.type === 'done').text, 'answer');
  assert.equal(provider.requests.length, 2);
  for (const [index, body] of provider.requests.entries()) {
    const headers = provider.headers[index];
    assert.equal(headers.authorization, 'Bearer oc-secret');
    assert.equal(headers['user-agent'], 'FarMap/1.0');
    assert.match(headers['x-opencode-session'], /^[a-f0-9]{64}$/);
    assert.equal(headers['x-opencode-session'], provider.headers[0]['x-opencode-session']);
    assert.equal(body.model, 'deepseek-v4.1-flash');
    assert.deepEqual(body.thinking, { type: 'disabled' });
    assert.ok(body.max_tokens);
    assert.equal(body.max_completion_tokens, undefined);
  }
  assert.ok(provider.requests[1].messages.some(message => message.role === 'tool'));
  assert.equal(provider.requests[0].tools[0].function.parameters.properties.query.type, 'string');
  assert.ok(!JSON.stringify(outputs).includes('oc-secret'));
  assert.ok(!JSON.stringify(outputs).includes('private environment'));
  assert.ok(!JSON.stringify(outputs).includes('opencode-return'));
});

test('OpenCode with a missing key fails without falling back to the legacy model', () => {
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: { FARMAP_OPENCODE_ENABLED: 'true', FARMAP_PI_API_KEY: 'legacy' } });
  runtime.handle(start('one', 's'));
  assert.equal(outputs[0].code, 'PROVIDER_ERROR');
  assert.equal(runtime.sessions.size, 0);
});

test('guest sessions remain tool-free and are removed after completion', async t => {
  const provider = await mockProvider(t, ({ answer }) => answer('answer'));
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('owner', 'owner:1', { tools: [], toolsetId: undefined }));
  await wait(() => outputs.some(value => value.id === 'owner' && value.type === 'done'));
  runtime.handle(start('guest', 'guest:random', { tools: [], toolsetId: undefined }));
  await wait(() => outputs.some(value => value.id === 'guest' && value.type === 'done'));
  await wait(() => !runtime.sessions.has('guest:random'));
  assert.ok(runtime.sessions.has('owner:1'));
  assert.equal(runtime.sessions.has('guest:random'), false);
  assert.ok(!provider.requests[1].tools || provider.requests[1].tools.length === 0);
});

test('start validation enforces tool registration bounds and tool responses bypass session checks', () => {
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value) });
  runtime.handle({ ...start('missing-toolset', 's'), toolsetId: undefined });
  assert.equal(outputs.at(-1).code, 'INVALID_REQUEST');
  runtime.handle({ ...start('large-environment', 's'), environment: 'x'.repeat(30001) });
  assert.equal(outputs.at(-1).code, 'INVALID_REQUEST');
  runtime.handle({ ...start('bad-schema', 's'), tools: [{ name: 'lookup', description: 'Lookup', parameters: null }] });
  assert.equal(outputs.at(-1).code, 'INVALID_REQUEST');
  runtime.handle({ ...start('empty-message', 's'), message: '' });
  assert.equal(outputs.at(-1).code, 'INVALID_REQUEST');
  runtime.handle({ id: 'abort', op: 'abort', session: 'unknown' });
  assert.equal(outputs.at(-1).code, 'SESSION_NOT_FOUND');
  const beforeResponse = outputs.length;
  runtime.handle({ id: 'unknown', op: 'tool_call_response', callId: 'missing', text: '{}', isError: false });
  assert.equal(outputs.length, beforeResponse);
  assert.equal(runtime.sessions.size, 0);
});

test('trimming retains a system baseline and complete user-led suffix', () => {
  const messages = [{ role: 'system', content: 'x' }, ...Array.from({ length: 50 }, (_, index) => ({ role: 'user', content: String(index) }))];
  const result = trimContext(messages);
  assert.equal(result[0].role, 'system');
  assert.equal(result[1].content, '10');
  assert.equal(result.length, 41);
});

test('versioned abort reserves cancellation before start and for a newer version', () => {
  const outputs = [];
  let calls = 0;
  const runtime = createRuntime({ emit: value => outputs.push(value), stream: () => { calls++; throw new Error('provider must not be invoked'); } });
  runtime.handle({ id: 'cancel', op: 'abort', session: 's', contextVersion: 4 });
  runtime.handle({ ...start('start', 's'), contextVersion: 4, scope: 'a' });
  assert.deepEqual(outputs.find(value => value.id === 'start'), { id: 'start', type: 'done', text: '', status: 'aborted' });
  assert.equal(calls, 0);
  runtime.sessions.set('s', { key: 's', version: 5, active: false, updated: Date.now() });
  runtime.handle({ id: 'new-cancel', op: 'abort', session: 's', contextVersion: 6 });
  runtime.handle({ ...start('new-start', 's'), contextVersion: 6, scope: 'a' });
  assert.equal(outputs.find(value => value.id === 'new-start').status, 'aborted');
  assert.equal(calls, 0);
  assert.equal(runtime.sessions.get('s').version, 5);
  runtime.handle({ id: 'stale', op: 'abort', session: 's', contextVersion: 4 });
  assert.equal(outputs.find(value => value.id === 'stale').code, 'STALE_VERSION');
});

test('shutdown releases pending calls and suppresses late data', async t => {
  const provider = await mockProvider(t, ({ toolCalls }) => {
    toolCalls([{ id: 'provider-shutdown', name: 'lookup', args: { query: 'wait' } }]);
  });
  const outputs = [];
  const runtime = createRuntime({ emit: value => outputs.push(value), env: providerEnv(provider.url) });
  runtime.handle(start('shutdown', 's'));
  await wait(() => toolRequests(outputs).length === 1);
  const call = toolRequests(outputs)[0];
  const session = runtime.sessions.get('s');
  const idle = session.agent.waitForIdle();
  assert.equal(runtime.pending.size, 1);
  runtime.shutdown();
  await Promise.race([idle, delay(1000).then(() => { throw new Error('agent did not settle'); })]);
  assert.equal(runtime.pending.size, 0);
  assert.equal(runtime.sessions.size, 0);
  const beforeLate = outputs.length;
  runtime.handle({ op: 'tool_call_response', id: 'shutdown', callId: call.callId, text: '{"value":"shutdown-late"}', isError: false });
  await delay(30);
  assert.equal(outputs.length, beforeLate);
  assert.ok(!JSON.stringify(outputs).includes('shutdown-late'));
});
