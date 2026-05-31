package com.maple

import com.maple.agent.AgentController
import com.maple.command.AICommand
import com.maple.config.MCMindConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

object MCMind : ModInitializer {
    private val logger = LoggerFactory.getLogger("mc-mind")
    private lateinit var config: MCMindConfig
    private var controller: AgentController? = null

    override fun onInitialize() {
        logger.info("MC-Mind 初始化中...")

        // 加载配置
        config = MCMindConfig.load()
        logger.info("配置已加载：API=${config.apiUrl}, 模型=${config.model}")

        // 注册命令
        AICommand.setConfig(config)
        AICommand.register()

        // 在服务器启动时初始化 AgentController
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            controller = AgentController(config, server)
            AICommand.setController(controller!!)
            logger.info("MC-Mind 服务器初始化完成！")
        }

        // 在服务器停止时清理
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            controller?.killAll()
            logger.info("MC-Mind 已停止")
        }

        logger.info("MC-Mind 注册完成，等待服务器启动...")
    }
}
