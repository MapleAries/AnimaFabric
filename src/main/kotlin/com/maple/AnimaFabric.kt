package com.maple

import com.maple.agent.AgentController
import com.maple.command.AICommand
import com.maple.config.AnimaFabricConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

object AnimaFabric : ModInitializer {
    private val logger = LoggerFactory.getLogger("anima-fabric")
    private lateinit var config: AnimaFabricConfig
    private var controller: AgentController? = null

    override fun onInitialize() {
        logger.info("织灵 (AnimaFabric) 初始化中...")

        // 加载配置
        config = AnimaFabricConfig.load()
        logger.info("配置已加载：API=${config.apiUrl}, 模型=${config.model}")

        // 注册命令
        AICommand.setConfig(config)
        AICommand.register()

        // 在服务器启动时初始化 AgentController
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            controller = AgentController(config, server)
            AICommand.setController(controller!!)
            logger.info("织灵服务器初始化完成！")
        }

        // 在服务器停止时清理
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            controller?.killAll()
            logger.info("织灵已停止")
        }

        logger.info("织灵注册完成，等待服务器启动...")
    }
}
