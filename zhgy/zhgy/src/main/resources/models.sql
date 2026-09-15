/*
 Navicat Premium Data Transfer

 Source Server         : zhgy
 Source Server Type    : MySQL
 Source Server Version : 80031
 Source Host           : localhost:3307
 Source Schema         : zhgy

 Target Server Type    : MySQL
 Target Server Version : 80031
 File Encoding         : 65001

 Date: 04/08/2025 21:30:00
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for models
-- ----------------------------
DROP TABLE IF EXISTS `models`;
CREATE TABLE `models`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `farm_id` int NOT NULL,
  `model_order` int NOT NULL DEFAULT 0,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `req_type` enum('text','figure','hybrid') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '请求类型：text(只需要文本), figure(只需要图片), hybrid(两者都需要)',
  `res_type` enum('text','figure','hybrid') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '响应类型：text(只需要文本), figure(只需要图片), hybrid(两者都需要)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_farm_id`(`farm_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of models
-- ----------------------------
INSERT INTO `models` VALUES (1, 1, 1, '沃柑病虫害检测模型', 'https://xxx.xxx.com/xxx', 'figure', 'hybrid');
INSERT INTO `models` VALUES (2, 1, 2, '产量预测模型', 'https://yyy.yyy.com/yyy', 'hybrid', 'text');
INSERT INTO `models` VALUES (3, 1, 3, '果实成熟度分析模型', 'https://zzz.zzz.com/zzz', 'figure', 'figure');
INSERT INTO `models` VALUES (4, 2, 1, '苹果病虫害识别模型', 'https://aaa.aaa.com/aaa', 'figure', 'hybrid');
INSERT INTO `models` VALUES (5, 2, 2, '土壤分析模型', 'https://bbb.bbb.com/bbb', 'hybrid', 'text');
INSERT INTO `models` VALUES (6, 3, 1, '柑橘品质评估模型', 'https://ccc.ccc.com/ccc', 'figure', 'hybrid');

SET FOREIGN_KEY_CHECKS = 1;