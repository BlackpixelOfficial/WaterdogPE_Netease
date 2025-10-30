package dev.waterdog.waterdogpe.network.netease;

import lombok.extern.log4j.Log4j2;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;

/**
 * Netease协议处理工具类
 */
@Log4j2
public class NetEaseUtils {
    
    /**
     * 检查给定的协议版本和数据是否表示Netease客户端
     */
    public static boolean isNetEaseClient(int raknetProtocol, int protocolVersion) {
        // config.yml中，需开启配置
        ProxyConfig config = ProxyServer.getInstance().getConfiguration();
        if (!config.netEaseClient()) {
            return false;
        }

        // 识别条件：raknetProtocol == 8 且 protocol == 1.21.2的版本
        if (raknetProtocol == 8 && protocolVersion == ProtocolVersion.MINECRAFT_PE_1_21_2.getProtocol()) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查给定的协议版本和数据是否表示Netease客户端
     */
    public static boolean isNetEaseClient(int raknetProtocol) {
        // 简化版本，不使用协议进行判断
        return isNetEaseClient(raknetProtocol, ProtocolVersion.MINECRAFT_PE_1_21_2.getProtocol());
    }

}