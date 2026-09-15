/*
数据库初始化脚本
包含weather表结构和示例数据
*/

-- 创建weather表
CREATE TABLE IF NOT EXISTS weather (
    id INT PRIMARY KEY AUTO_INCREMENT,
    farm_id INT NOT NULL,
    farm_type VARCHAR(50) NOT NULL,
    month INT NOT NULL CHECK (month >= 0 AND month <= 11),
    text TEXT NOT NULL,
    FOREIGN KEY (farm_id) REFERENCES farm(id)
);

-- 创建accumulated_temperature表(积温表)
CREATE TABLE IF NOT EXISTS accumulated_temperature (
    id INT PRIMARY KEY AUTO_INCREMENT,
    farm_id INT NOT NULL,
    date DATE NOT NULL,
    temperature DECIMAL(5,2) NOT NULL, -- 当日平均温度
    accumulated_temp DECIMAL(8,2) NOT NULL, -- 累积温度
    FOREIGN KEY (farm_id) REFERENCES farm(id),
    UNIQUE KEY unique_farm_date (farm_id, date)
);

-- 插入示例数据
INSERT INTO weather (farm_id, farm_type, month, text) VALUES
-- 柑橘农场示例
(1, 'citrus', 0, '1月的节气包括小寒和大寒，寒冷气候达到顶点，注意保护果树根系防止冻害，为来年春季做准备。'),
(1, 'citrus', 0, '沃柑的采收期，花芽形态分化期，冬梢萌发期。'),
(1, 'citrus', 1, '2月的节气为雨水，气温逐渐回升，但仍有寒潮，注意防冻。'),
(1, 'citrus', 1, '沃柑的花芽萌动期，春梢萌发期。'),
(1, 'citrus', 2, '3月的节气为惊蛰和春分，气温回升较快，雨水较多。'),
(1, 'citrus', 2, '沃柑的开花期，需要做好授粉管理。'),
(1, 'citrus', 3, '4月的节气为清明和谷雨，气温进一步回升，雨水充沛。'),
(1, 'citrus', 3, '沃柑的谢花期，第一次生理落果期。'),
(1, 'citrus', 4, '5月的节气为立夏和小满，气温明显升高，进入夏季。'),
(1, 'citrus', 4, '沃柑的第二次生理落果期，夏梢萌发期。'),
(1, 'citrus', 5, '6月的节气为芒种和夏至，气温高，雨水多。'),
(1, 'citrus', 5, '沃柑的果实膨大期，需要加强水肥管理。'),
(1, 'citrus', 6, '7月的节气为小暑和大暑，一年中最热的时期。'),
(1, 'citrus', 6, '沃柑的果实快速膨大期，注意防日灼。'),
(1, 'citrus', 7, '8月的节气为立秋和处暑，炎热稍退，但仍有高温。'),
(1, 'citrus', 7, '沃柑的果实继续膨大期，秋梢萌发期。'),
(1, 'citrus', 8, '9月的节气为白露和秋分，昼夜温差加大。'),
(1, 'citrus', 8, '沃柑的果实成熟期，秋梢生长期。'),
(1, 'citrus', 9, '10月的节气为寒露和霜降，气温下降明显。'),
(1, 'citrus', 9, '沃柑的果实着色期，需要控制水分。'),
(1, 'citrus', 10, '11月的节气为立冬和小雪，气温明显下降。'),
(1, 'citrus', 10, '沃柑的果实成熟采收期，花芽生理分化期。'),
(1, 'citrus', 11, '12月的节气为大雪和冬至，进入寒冷季节。'),
(1, 'citrus', 11, '沃柑的花芽形态分化期，冬梢萌发期。'),

-- 苹果园示例
(2, 'apple', 0, '1月的节气包括小寒和大寒，寒冷气候达到顶点，注意保护果树根系防止冻害。'),
(2, 'apple', 0, '苹果树的休眠期，整形修剪期。'),
(2, 'apple', 1, '2月的节气为雨水，气温逐渐回升，但仍有寒潮，注意防冻。'),
(2, 'apple', 1, '苹果树的萌芽前期，可以进行嫁接。'),
(2, 'apple', 2, '3月的节气为惊蛰和春分，气温回升较快，雨水较多。'),
(2, 'apple', 2, '苹果树的萌芽期，花芽萌动期。'),
(2, 'apple', 3, '4月的节气为清明和谷雨，气温进一步回升，雨水充沛。'),
(2, 'apple', 3, '苹果树的开花期，需要做好授粉管理。'),
(2, 'apple', 4, '5月的节气为立夏和小满，气温明显升高，进入夏季。'),
(2, 'apple', 4, '苹果树的谢花期，幼果发育期。'),
(2, 'apple', 5, '6月的节气为芒种和夏至，气温高，雨水多。'),
(2, 'apple', 5, '苹果树的果实膨大期，需要加强水肥管理。'),
(2, 'apple', 6, '7月的节气为小暑和大暑，一年中最热的时期。'),
(2, 'apple', 6, '苹果树的果实快速膨大期，注意病虫害防治。'),
(2, 'apple', 7, '8月的节气为立秋和处暑，炎热稍退，但仍有高温。'),
(2, 'apple', 7, '苹果树的果实继续膨大期，花芽分化期。'),
(2, 'apple', 8, '9月的节气为白露和秋分，昼夜温差加大。'),
(2, 'apple', 8, '苹果树的果实成熟期，秋施基肥期。'),
(2, 'apple', 9, '10月的节气为寒露和霜降，气温下降明显。'),
(2, 'apple', 9, '苹果树的果实采收期，秋施基肥期。'),
(2, 'apple', 10, '11月的节气为立冬和小雪，气温明显下降。'),
(2, 'apple', 10, '苹果树的落叶期，冬剪准备期。'),
(2, 'apple', 11, '12月的节气为大雪和冬至，进入寒冷季节。'),
(2, 'apple', 11, '苹果树的休眠期，整形修剪期。')
ON DUPLICATE KEY UPDATE text=text;