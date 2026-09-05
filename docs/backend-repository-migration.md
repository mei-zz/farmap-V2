# Backend repository migration

## Audit record

The backend was previously an independent Git repository nested at
`zhgy/zhgy` while the FarMap root repository tracked that directory as a
gitlink.

| Item | Recorded value |
| --- | --- |
| Original repository path | `D:\\前后端项目\\farmap\\zhgy\\zhgy` |
| Original branch | `v4` |
| Original commit | `3ca2064ffc7fb0f013bac86e1d9ec2bba3094f1f` |
| Original remote | `https://gitee.com/mei1201/farmap.git` |
| Working tree at audit | clean |
| Root gitlink at audit | `160000 3ca2064ffc7fb0f013bac86e1d9ec2bba3094f1f zhgy/zhgy` |

The Gitee remote and repository are preserved. No remote history is rewritten
and no data store is modified by this migration.

## Target layout

The FarMap V2 GitHub repository tracks the backend source directly so a fresh
clone contains `zhgy/zhgy/pom.xml` and `zhgy/zhgy/src/**` without depending on
the nested repository metadata.

Local runtime state and generated artifacts remain outside the monorepo via the
root `.gitignore`, including `target`, `.m2`, logs, uploads, local database
volumes, packaged jars, and IDE metadata. Existing files are preserved locally.

## Migration procedure

1. Confirm the nested backend worktree is clean and record its branch, commit,
   and remote.
2. Add the backend runtime exclusions to the root ignore rules.
3. Remove only `zhgy/zhgy/.git` after the audit record is created; this removes
   nested Git metadata, not backend source or runtime data.
4. Stage the backend source in the root repository and verify that the result
   contains `pom.xml` and Java test/source files rather than a `160000` gitlink.

The nested repository remains recoverable from the recorded Gitee remote and
commit SHA. This document is part of the root repository history to make the
source consolidation auditable.
