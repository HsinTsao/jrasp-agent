package com.jrasp.core.manager.impl;

import com.jrasp.api.ConfigInfo;
import com.jrasp.api.Module;
import com.jrasp.api.Resource;
import com.jrasp.api.log.Log;
import com.jrasp.core.CoreConfigure;
import com.jrasp.core.classloader.ProviderClassLoader;
import com.jrasp.core.log.LogFactory;
import com.jrasp.core.manager.ProviderManager;
import com.jrasp.provider.api.ModuleJarLoadingChain;
import com.jrasp.provider.api.ModuleLoadingChain;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.reflect.FieldUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ServiceLoader;

import static com.jrasp.core.log.AgentLogIdConstant.AGENT_COMMON_LOG_ID;

public class DefaultProviderManager implements ProviderManager {

    private final Log logger = LogFactory.getLog(getClass());
    private final Collection<ModuleJarLoadingChain> moduleJarLoadingChains = new ArrayList<ModuleJarLoadingChain>();
    private final Collection<ModuleLoadingChain> moduleLoadingChains = new ArrayList<ModuleLoadingChain>();
    private final CoreConfigure cfg;

    public DefaultProviderManager(final CoreConfigure cfg) {
        this.cfg = cfg;
        try {
            init(cfg);
        } catch (Throwable cause) {
            logger.warn(AGENT_COMMON_LOG_ID,"loading rasp's provider-lib[{}] failed.", cfg.getProviderLibPath(), cause);
        }
    }

    private void init(final CoreConfigure cfg) {
        final File providerLibDir = new File(cfg.getProviderLibPath());
        if (!providerLibDir.exists()
                || !providerLibDir.canRead()) {
            logger.warn(AGENT_COMMON_LOG_ID,"AGENT_COMMON_LOG_ID,loading provider-lib[{}] was failed, doest existed or access denied.", providerLibDir);
            return;
        }

        for (final File providerJarFile : FileUtils.listFiles(providerLibDir, new String[]{"jar"}, false)) {

            try {
                final ProviderClassLoader providerClassLoader = new ProviderClassLoader(providerJarFile, getClass().getClassLoader());

                // load ModuleJarLoadingChain
                inject(moduleJarLoadingChains, ModuleJarLoadingChain.class, providerClassLoader, providerJarFile);

                // load ModuleLoadingChain
                inject(moduleLoadingChains, ModuleLoadingChain.class, providerClassLoader, providerJarFile);

                logger.info(AGENT_COMMON_LOG_ID,"loading provider-jar[{}] was success.", providerJarFile);
            } catch (IllegalAccessException cause) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading provider-jar[{}] occur error, inject provider resource failed.", providerJarFile, cause);
            } catch (IOException ioe) {
                logger.warn(AGENT_COMMON_LOG_ID,"loading provider-jar[{}] occur error, ignore load this provider.", providerJarFile, ioe);
            }

        }

    }

    private <T> void inject(final Collection<T> collection,
                            final Class<T> clazz,
                            final ClassLoader providerClassLoader,
                            final File providerJarFile) throws IllegalAccessException {
        final ServiceLoader<T> serviceLoader = ServiceLoader.load(clazz, providerClassLoader);
        for (final T provider : serviceLoader) {
            injectResource(provider); // 注入Resource
            collection.add(provider); // 加入到 provider 链路
            logger.info(AGENT_COMMON_LOG_ID,"loading provider[{}] was success from provider-jar[{}], impl={}",
                    clazz.getName(), providerJarFile, provider.getClass().getName());
        }
    }

    // 给 provider.jar 中的对象注入 Resource
    private void injectResource(final Object provider) throws IllegalAccessException {
        final Field[] resourceFieldArray = FieldUtils.getFieldsWithAnnotation(provider.getClass(), Resource.class);
        if (ArrayUtils.isEmpty(resourceFieldArray)) {
            return;
        }
        for (final Field resourceField : resourceFieldArray) {
            final Class<?> fieldType = resourceField.getType();
            // ConfigInfo注入
            if (ConfigInfo.class.isAssignableFrom(fieldType)) {
                final ConfigInfo configInfo = new DefaultConfigInfo(cfg);
                FieldUtils.writeField(resourceField, provider, configInfo, true);
            }
        }
    }

    @Override
    public void loading(final File moduleJarFile) throws Throwable {
        for (final ModuleJarLoadingChain chain : moduleJarLoadingChains) {
            chain.loading(moduleJarFile);
        }
    }

    @Override
    public void loading(final String uniqueId,
                        final Class moduleClass,
                        final Module module,
                        final File moduleJarFile,
                        final ClassLoader moduleClassLoader) throws Throwable {
        for (final ModuleLoadingChain chain : moduleLoadingChains) {
            chain.loading(
                    uniqueId,
                    moduleClass,
                    module,
                    moduleJarFile,
                    moduleClassLoader
            );
        }
    }
}
