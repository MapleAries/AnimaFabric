package com.maple

import com.maple.agent.AgentController
import com.maple.command.AICommand
import com.maple.config.MCMindConfig
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object MCMind : ModInitializer {
    private val logger = LoggerFactory.getLogger("mc-mind")
    private lateinit var config: MCMindConfig
    private lateinit var controller: AgentController

    override fun onInitialize() {
        logger.info("MC-Mind 初始化中...")

        // 加载配置
        config = MCMindConfig.load()
        logger.info("配置已加载：API=${config.apiUrl}, 模型=${config.model}")

        // 初始化 AgentController
        controller = AgentController(config)
        AICommand.setController(controller)

        // 注册命令
        AICommand.register()

        logger.info("MC-Mind 初始化完成！")
    }
}
