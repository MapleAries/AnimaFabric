# AnimaFabric TODO

## ~~P0 — 寻路基础~~ ✅
- [x] 精确代价模型（tick 物理）
- [x] 18 种移动类型（水平/对角/上升/下降/垂直）
- [x] 超时 + 多系数 A*
- [x] Entity.move() 直接驱动移动

## ~~P1 — 方块与工具~~ ✅
- [x] 增强方块分类系统（BlockClassifier: 水/梯子/地毯/雪/门/半砖/台阶）
- [x] 工具感知挖掘代价（ToolCostCalculator: 工具类型匹配 + 等级倍率）

## P2 — 架构改进
- [ ] 回避系统（backtrack avoidance + mob avoidance）
- [ ] 区块边界感知（避免寻路到未加载区块）
- [ ] Long2ObjectOpenHashMap 优化节点存储
- [ ] Process/Behavior 分离架构
- [ ] 线程安全 CalculationContext 快照

## P3 — 任务与反馈
- [ ] 声明式任务模板（Malmo 风格 JSON Mission Schema）
- [ ] 可组合奖励系统
- [ ] 会话录制回放

## P3 — 高级功能
- [ ] 攀爬移动（梯子/藤蔓）
- [ ] 搭桥放置（侧放/后放优化）
- [ ] 路径拼接（Path Splicing）
- [ ] 移动中断安全检查
