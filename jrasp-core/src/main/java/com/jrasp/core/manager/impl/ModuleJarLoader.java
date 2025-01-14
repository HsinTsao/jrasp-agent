package com.jrasp.core.manager.impl;

import com.jrasp.api.Information;
import com.jrasp.api.Module;
import com.jrasp.api.log.Log;
import com.jrasp.core.classloader.ModuleJarClassLoader;
import com.jrasp.core.log.LogFactory;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.ServiceLoader;
import java.util.Set;

import static com.jrasp.core.log.AgentLogIdConstant.AGENT_COMMON_LOG_ID;

// jar文件加载
public class ModuleJarLoader {

    private final Log logger = LogFactory.getLog(getClass());

    // 等待加载的模块jar文件(源文件)
    private final File moduleJarFile;

    // copy路径
    private final File copyDir;

    // 沙箱加载模式
    private final Information.Mode mode;

    ModuleJarLoader(final File moduleJarFile, File copyDir,
                    final Information.Mode mode) {
        this.moduleJarFile = moduleJarFile;
        this.copyDir = copyDir;
        this.mode = mode;
    }

    void load(final ModuleLoadCallback mCb) throws IOException {
        boolean hasModuleLoadedSuccessFlag = false;
        ModuleJarClassLoader moduleJarClassLoader = null;
        logger.info(AGENT_COMMON_LOG_ID,"prepare loading module-jar={};", moduleJarFile);
        try {
            moduleJarClassLoader = new ModuleJarClassLoader(moduleJarFile,copyDir,true);
            final ClassLoader preTCL = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(moduleJarClassLoader);
            try {
                hasModuleLoadedSuccessFlag = loadingModules(moduleJarClassLoader, mCb);
            } finally {
                Thread.currentThread().setContextClassLoader(preTCL);
            }
        } finally {
            // 模块加载失败尝试清理jar
            if (!hasModuleLoadedSuccessFlag
                    && null != moduleJarClassLoader) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading module-jar completed, but NONE module loaded, will be close ModuleJarClassLoader. module-jar={};", moduleJarFile);
                moduleJarClassLoader.closeIfPossible();
            }
        }
    }

    // 加载模块类并判断模块中module上的注解信息
    private boolean loadingModules(final ModuleJarClassLoader moduleClassLoader,
                                   final ModuleLoadCallback mCb) {
        final Set<String> loadedModuleUniqueIds = new LinkedHashSet<String>(); // 仅用于记录modules个数在打印日志时
        // todo 怎么加载的？？ 有点类似于服务发现
        final ServiceLoader<Module> moduleServiceLoader = ServiceLoader.load(Module.class, moduleClassLoader);
        final Iterator<Module> moduleIt = moduleServiceLoader.iterator();
        while (moduleIt.hasNext()) {
            final Module module;
            try {
                module = moduleIt.next();
            } catch (Throwable cause) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading module instance failed: instance occur error, will be ignored. module-jar={}", moduleJarFile, cause);
                continue;
            }
            final Class<?> classOfModule = module.getClass();
            // 判断模块是否实现了@Information标记
            if (!classOfModule.isAnnotationPresent(Information.class)) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading module instance failed: not implements @Information, will be ignored. class={};module-jar={};",
                        classOfModule,
                        moduleJarFile
                );
                continue;
            }
            final Information info = classOfModule.getAnnotation(Information.class);
            final String uniqueId = info.id();
            // 判断模块ID是否合法
            if (StringUtils.isBlank(uniqueId)) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading module instance failed: @Information.id is missing, will be ignored. class={};module-jar={};",
                        classOfModule,
                        moduleJarFile
                );
                continue;
            }
            // 判断模块要求的启动模式和容器的启动模式是否匹配
            // todo 目前看到的模块没有强制要求agent启动方式
            if (!ArrayUtils.contains(info.mode(), mode)) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading module instance failed: launch-mode is not match module required, will be ignored. module={};launch-mode={};required-mode={};class={};module-jar={};",
                        uniqueId,
                        mode,
                        StringUtils.join(info.mode(), ","),
                        classOfModule,
                        moduleJarFile
                );
                continue;
            }

            try {
                if (null != mCb) {
                    mCb.onLoad(uniqueId, classOfModule, module, moduleJarFile, moduleClassLoader);
                }
            } catch (Throwable cause) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading module instance failed: MODULE-LOADER-PROVIDER denied, will be ignored. module={};class={};module-jar={};",
                        uniqueId,
                        classOfModule,
                        moduleJarFile,
                        cause
                );
                continue;
            }

            loadedModuleUniqueIds.add(uniqueId);

        }


        logger.info(AGENT_COMMON_LOG_ID,"loaded module-jar completed, loaded {} module in module-jar={}, modules={}",
                loadedModuleUniqueIds.size(),
                moduleJarFile,
                loadedModuleUniqueIds
        );
        return !loadedModuleUniqueIds.isEmpty();
    }

    /**
     * 模块加载回调
     */
    public interface ModuleLoadCallback {

        /**
         * 模块加载回调
         *
         * @param uniqueId          模块ID
         * @param moduleClass       模块类
         * @param module            模块实例
         * @param moduleJarFile     模块所在Jar文件
         * @param moduleClassLoader 负责加载模块的ClassLoader
         * @throws Throwable 加载回调异常
         */
        void onLoad(String uniqueId,
                    Class moduleClass,
                    Module module,
                    File moduleJarFile,
                    ModuleJarClassLoader moduleClassLoader) throws Throwable;

    }

}
