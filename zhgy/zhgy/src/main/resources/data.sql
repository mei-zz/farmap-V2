/*
数据库初始化脚本
包含用户、农场、组件等测试数据
*/

-- 插入用户数据
INSERT INTO user (id, username, password, role) VALUES 
(1, 'guest', '123456', 'guest'),
(2, 'admin', 'admin123', 'admin'),
(3, 'farmer1', 'farmer123', 'guest'),
(4, 'farmer2', 'farmer456', 'guest')
ON DUPLICATE KEY UPDATE username=username;

-- 插入组件数据
INSERT INTO component (id, name) VALUES 
(1, 'nep'),
(2, 'guidance'),
(3, 'weather'),
(4, 'statistic')
ON DUPLICATE KEY UPDATE name=name;

-- 插入农场数据
INSERT INTO farm (id, name, type, address, zoom, center, username, user_id) VALUES 
(1, 'GUEST_FARM', 'citrus', 'xxx社区', 12, '12.123,102.123', 'guest', 1),
(2, 'ADMIN_FARM_1', 'apple', 'yyy社区', 13, '15.456,105.456', 'admin', 2),
(3, 'FARMER1_FARM', 'orange', 'zzz社区', 14, '18.789,108.789', 'farmer1', 3),
(4, 'FARMER2_FARM_1', 'banana', 'aaa社区', 15, '20.111,110.111', 'farmer2', 4),
(5, 'FARMER2_FARM_2', 'grape', 'bbb社区', 16, '22.222,112.222', 'farmer2', 4)
ON DUPLICATE KEY UPDATE name=name;

-- 插入农场组件关联数据
INSERT INTO farm_component (farm_id, component_id) VALUES 
(1, 1), (1, 2), (1, 3), (1, 4),
(2, 1), (2, 3),
(3, 2), (3, 4),
(4, 1), (4, 2), (4, 3),
(5, 3), (5, 4)
ON DUPLICATE KEY UPDATE farm_id=farm_id;

-- 插入位置数据
INSERT INTO location (id, farm_id, longitude, latitude) VALUES 
(1, 1, 12.123, 102.123),
(2, 1, 12.124, 102.124),
(3, 2, 15.456, 105.456),
(4, 2, 15.457, 105.457),
(5, 3, 18.789, 108.789),
(6, 4, 20.111, 110.111),
(7, 5, 22.222, 112.222)
ON DUPLICATE KEY UPDATE longitude=longitude;

-- 插入作物数据
INSERT INTO crop (id, farm_id, longitude, latitude, date, yield, size, rate, diseases, url) VALUES 
(1, 1, 12.123, 102.123, '2025-07-31', 123, 1.23, 9.12, 'xx;yyy;zzz', 'https://a.b.c/xxx'),
(2, 2, 15.456, 105.456, '2025-08-01', 234, 2.34, 8.23, 'aa;bbb;ccc', 'https://d.e.f/yyy'),
(3, 3, 18.789, 108.789, '2025-08-02', 345, 3.45, 7.34, 'dd;eee;fff', 'https://g.h.i/zzz'),
(4, 4, 20.111, 110.111, '2025-08-03', 456, 4.56, 6.45, 'gg;hhh;iii', 'https://j.k.l/aaa'),
(5, 5, 22.222, 112.222, '2025-08-04', 567, 5.67, 5.67, 'jj;kkk;lll', 'https://m.n.o/bbb')
ON DUPLICATE KEY UPDATE yield=yield;