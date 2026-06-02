# AnimaFabric TODO

## ~~P0 — 寻路基础~~ ✅
- [x] 精确代价模型（ActionCosts: tick 物理）
- [x] 18 种移动类型（MovementType: 水平/对角/上升/下降/垂直）
- [x] 超时 + 多系数 A*（AStarPathfinder 增强版）
- [x] Entity.move() 直接驱动移动

## ~~P1 — 方块与工具~~ ✅
- [x] 增强方块分类系统（BlockClassifier: 水/梯子/地毯/雪/门/半砖/台阶）
- [x] 工具感知挖掘代价（ToolCostCalculator: 工具类型匹配 + 等级倍率）

## ~~P2 — 架构改进~~ ✅
- [x] 回避系统（backtrack avoidance + mob avoidance）
- [x] 区块边界感知（避免寻路到未加载区块）
- [x] Long hash 优化节点存储
- [x] Process/Behavior 分离架构（Process + Behavior 基类）
- [x] 线程安全 CalculationContext 快照（方块状态缓存）

## ~~P3 — 任务与反馈~~ ✅
- [x] 声明式任务模板（Mission: Malmo 风格 JSON Schema）
- [x] 可组合奖励系统（RewardSystem + 多种 RewardHandler）
- [x] 会话录制回放（SessionRecorder: 帧级记录）

## ~~P3 — 高级功能~~ ✅
- [x] 攀爬移动（MovementExecutor: 梯子/藤蔓检测）
- [x] 搭桥放置（MovementExecutor: 潜行+放置方块）
- [x] 路径拼接（MovementExecutor.splicePath()）
- [x] 移动中断安全检查（路径偏离 + 卡住检测）

## 后续优化
- [ ] LLM 响应解析优化（DeepSeek 模型适配）
- [ ] 多 Bot 协作
- [ ] 环境建图（大范围世界模型）
- [ ] 事件响应（袭击/末影龙等场景）
