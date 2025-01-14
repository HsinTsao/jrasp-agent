package com.jrasp.module.admin;

import com.jrasp.api.ConfigInfo;
import com.jrasp.api.Information;
import com.jrasp.api.Module;
import com.jrasp.api.Resource;
import com.jrasp.api.annotation.Command;
import com.jrasp.api.json.JSONObject;
import com.jrasp.api.log.Log;
import com.jrasp.api.model.RestResultUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.kohsuke.MetaInfServices;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;

import static com.jrasp.module.admin.AdminModuleLogIdConstant.*;

@MetaInfServices(Module.class)
@Information(id = "control", version = "0.0.1", author = "jrasp")
public class ControlModule implements Module {

    @Resource
    private Log logger;

    @Resource
    private JSONObject jsonObject;

    @Resource
    private ConfigInfo configInfo;

    // 卸载jvm-rasp
    private void uninstall() throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Class<?> classOfAgentLauncher = getClass().getClassLoader()
                .loadClass("com.jrasp.agent.AgentLauncher");
        logger.info(CONTROL_MODULE_UNINSTALL_LOG_ID,"uninstall jrasp agent{}", configInfo.getNamespace());
        MethodUtils.invokeStaticMethod(
                classOfAgentLauncher,
                "uninstall",
                configInfo.getNamespace()
        );
    }

    @Command("shutdown")
    public void shutdown(final PrintWriter writer) {
        logger.info(CONTROL_MODULE_SHUTDOWN_LOG_ID,"prepare to shutdown jvm-rasp[{}].", configInfo.getNamespace());
        // 关闭HTTP服务器
        final Thread shutdownJvmRaspHook = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    uninstall();
                } catch (Throwable cause) {
                    logger.warn(CONTROL_MODULE_SHUTDOWN_ERROR_LOG_ID,"shutdown jvm-rasp failed.", cause);
                }
            }
        }, "shutdown-jvm-rasp-hook");
        shutdownJvmRaspHook.setDaemon(true);

        // 在卸载自己之前，先向这个世界发出最后的呐喊吧！
        writer.println(jsonObject.toFormatJSONString(RestResultUtils.success("shutdown success", null)));
        writer.flush();
        writer.close();

        shutdownJvmRaspHook.start();
    }

}
