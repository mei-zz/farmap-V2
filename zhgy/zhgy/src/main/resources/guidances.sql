/*
数据库初始化脚本
包含guidances表结构和示例数据
*/

-- 创建guidances表
CREATE TABLE IF NOT EXISTS guidances (
    id INT PRIMARY KEY AUTO_INCREMENT,
    farm_id INT NOT NULL,
    farm_type VARCHAR(50) NOT NULL,
    month INT NOT NULL CHECK (month >= 0 AND month <= 11),
    guidance_type ENUM('body', 'fertite', 'pest', 'park') NOT NULL,
    text TEXT NOT NULL,
    is_formula BOOLEAN DEFAULT FALSE,
    FOREIGN KEY (farm_id) REFERENCES farm(id)
);

-- 插入示例数据
INSERT INTO guidances (farm_id, farm_type, month, guidance_type, text, is_formula) VALUES
-- 树体管理示例
(1, 'citrus', 0, 'body', '冬季修剪，剪除病虫枝、枯枝、交叉枝', FALSE),
(1, 'citrus', 1, 'body', '春季抹芽，控制春梢数量', FALSE),
(1, 'citrus', 2, 'body', '花期疏花，提高坐果率', FALSE),
(1, 'citrus', 3, 'body', '幼果期疏果，保持合理叶果比', FALSE),

-- 水肥管理示例
(1, 'citrus', 0, 'fertite', '施用有机肥，每株2-3公斤', FALSE),
(1, 'citrus', 1, 'fertite', '春季萌芽肥，氮磷钾复合肥', FALSE),
(1, 'citrus', 2, 'fertite', '开花前追肥，以磷钾肥为主', FALSE),
(1, 'citrus', 3, 'fertite', '果实膨大期施肥，钾肥为主', TRUE),
(1, 'citrus', 3, 'fertite', '配方：硫酸钾20g，磷酸二氢钾15g', TRUE),

-- 病虫管理示例
(1, 'citrus', 0, 'pest', '冬季清园，喷施石硫合剂', FALSE),
(1, 'citrus', 1, 'pest', '防治蚜虫，可使用吡虫啉', FALSE),
(1, 'citrus', 2, 'pest', '花期注意防治蓟马', FALSE),
(1, 'citrus', 3, 'pest', '幼果期防治红蜘蛛', TRUE),
(1, 'citrus', 3, 'pest', '配方：阿维菌素2000倍液+螺螨酯3000倍液', TRUE),

-- 清园操作示例
(1, 'citrus', 0, 'park', '清理果园杂草，集中处理病虫枝', FALSE),
(1, 'citrus', 6, 'park', '夏季中耕除草，保持土壤疏松', FALSE),
(1, 'citrus', 9, 'park', '秋季深翻土壤，施入有机肥', FALSE),

-- 其他农场示例
(2, 'apple', 0, 'body', '苹果树冬季整形修剪', FALSE),
(2, 'apple', 0, 'fertite', '施用基肥，以有机肥为主', FALSE),
(2, 'apple', 0, 'pest', '冬季病虫害防治', FALSE),
(2, 'apple', 0, 'park', '果园冬季清理', FALSE),

(3, 'orange', 5, 'fertite', '夏季追肥，促进果实发育', FALSE),
(3, 'orange', 5, 'fertite', '配方：尿素30g，过磷酸钙50g，硫酸钾20g', TRUE),
(3, 'orange', 5, 'pest', '防治蚧壳虫和红蜘蛛', TRUE),
(3, 'orange', 5, 'pest', '配方：矿物油200倍液+阿维菌素3000倍液', TRUE)
ON DUPLICATE KEY UPDATE text=text;