/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.forgerock.openidm.config.installer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.felix.cm.file.ConfigurationHandler;
import org.apache.felix.fileinstall.ArtifactInstaller;
import org.apache.felix.fileinstall.ArtifactListener;
import org.apache.felix.fileinstall.internal.DirectoryWatcher;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Deactivate;
import org.forgerock.openidm.config.JSONEnhancedConfig;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ArtifactInstaller for JSON configurations. Based on ConfigInstaller.
 * TODO: This service lifecycle should be bound to the ConfigurationAdmin service lifecycle.
 */
public class JSONConfigInstaller implements ArtifactInstaller, ConfigurationListener {

    // The key in the OSGi configuration dictionary holding the complete JSON configuration string
    public final static String JSON_CONFIG_PROPERTY = JSONEnhancedConfig.JSON_CONFIG_PROPERTY;
    
    public final static String SERVICE_FACTORY_PID = "config.factory-pid";
    public final static String DEFAULT_SERVICE_RDN_PREFIX = "org.forgerock.openidm.";
    
    final static Logger logger = LoggerFactory.getLogger(JSONConfigInstaller.class);
    
    private BundleContext context;
    private ConfigurationAdmin configAdmin;

    public void start(BundleContext ctx) {
        this.context = ctx;
        this.configAdmin = lookupConfigAdmin(context);
        if (this.configAdmin != null) {
            logger.debug("Starting JSON configuration listener");
        } else {
            logger.debug("ConfigAdmin is not yet available for JSON configuration listener, " 
                    + " will not handle JSON configuration files until it is available.");
        }
    }

    public void stop(BundleContext ctx) {
        this.context = null;
        this.configAdmin = null;
        logger.debug("Stopped JSON configuration listener");        
    }
    
    public boolean canHandle(File artifact)
    {
        if (this.configAdmin == null) {
            // See if the configuration admin service is available now
            this.configAdmin = lookupConfigAdmin(this.context);
            if (this.configAdmin != null) {
                logger.info("Detected ConfigAdmin service, starting JSON configuration listener");
            }
        }
       
        if (this.configAdmin != null) {
            logger.debug("Checking if can handle artifact: " + artifact);
            return artifact.getName().endsWith(".json");
        } else {
            return false;
        }
    }

    public void install(File artifact) throws Exception
    {
        logger.debug("Artifact install ", artifact);
        setConfig(artifact);
    }

    public void update(File artifact) throws Exception
    {
        logger.debug("Artifact update ", artifact);
        setConfig(artifact);
    }

    public void uninstall(File artifact) throws Exception
    {
        logger.debug("Artifact uninstall ", artifact);
        deleteConfig(artifact);
    }

    public void configurationEvent(ConfigurationEvent configurationEvent)
    {    
        logger.debug("ConfigurationEvent ", configurationEvent);
        // Check if writing back configurations has been disabled.
        {
            Object obj = this.context.getProperty( DirectoryWatcher.DISABLE_CONFIG_SAVE );
            if (obj instanceof String) {
                obj = new Boolean((String) obj );
            }
            if( Boolean.FALSE.equals( obj ) )
            {
                return;
            }
        }
        if (configurationEvent.getType() == ConfigurationEvent.CM_UPDATED)
        {
            try
            {
                Configuration config = getConfigurationAdmin().getConfiguration(
                                            configurationEvent.getPid(),
                                            configurationEvent.getFactoryPid());
                Dictionary dict = config.getProperties();
                String fileName = (String) dict.get( DirectoryWatcher.FILENAME );
                File file = fileName != null ? fromConfigKey(fileName) : null;
                if( file != null && file.isFile()   ) {
                    if (fileName.endsWith(".json")) {
                        logger.debug("Updating configuration file " + fileName);
                        // Note: currently only stores JSON config property, not other properties in Dictionary.
                        String jsonConfig = "";
                        Object entry = dict.get(JSON_CONFIG_PROPERTY);
                        if (entry != null) {
                            jsonConfig = entry.toString(); 
                        }
                        Writer writer = new OutputStreamWriter(new FileOutputStream(file));
                        try {
                          writer.write(jsonConfig);
                        } finally {
                          writer.close();
                        }
                    }
                }
            } catch (Exception e) {
                logger.info("Unable to save configuration", e);
            }
        }
    }

    public static ConfigurationAdmin lookupConfigAdmin(BundleContext context) {
        ConfigurationAdmin confAdmin = null;
        if (context != null) {
            ServiceReference configurationAdminReference = 
                    context.getServiceReference(ConfigurationAdmin.class.getName()); 
            if (configurationAdminReference != null) {
                confAdmin = (org.osgi.service.cm.ConfigurationAdmin) context.getService(configurationAdminReference);
            }
        }
        return confAdmin;
    }
    
    public ConfigurationAdmin getConfigurationAdmin()
    {
        // TOOD: better guarding against this service not (yet) being there.
        if (configAdmin == null) {
            this.configAdmin = lookupConfigAdmin(context);
            if (this.configAdmin != null) {
                logger.info("ConfigAdmin service detected by JSON configuration listener");
            } else {
                logger.warn("JSON Configuration listener could not find ConfigAdmin service");
            }
        }
        
        return configAdmin;
    }

    /**
     * Load the specified configuration file as hashtable. 
     * 
     * May also be called by other clients that want direct access to a given configuration file
     * without going through the configuration admin service.
     * 
     * @param f
     * @return
     */
    @SuppressWarnings("unchecked")
    public static Hashtable loadConfigFile(final File f) throws IOException, FileNotFoundException {
        logger.debug("Loading configuration from {}", f);
        final Hashtable ht = new Hashtable();
        final InputStream in = new BufferedInputStream(new FileInputStream(f));
        try {
            if (f.getName().endsWith( ".json")) {
                
                StringBuffer fileBuf = new StringBuffer(1024);
                BufferedReader reader = new BufferedReader(new FileReader(f));
                try {
                    char[] buf = new char[1024];
                    int numRead = 0;
                    while((numRead = reader.read(buf)) != -1){
                        fileBuf.append(buf, 0, numRead);
                    }
                } finally {
                    reader.close();
                }

                ht.put(JSON_CONFIG_PROPERTY, fileBuf.toString());
            }
        }
        finally{
            in.close();
        }
        return ht;
    }
    
    /**
     * Set the configuration based on the config file.
     *
     * @param f
     *            Configuration file
     * @return
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    boolean setConfig(final File f) throws Exception
    {
        boolean updated = false;
        try {
            final Hashtable ht = loadConfigFile(f);
    
            String pid[] = parsePid(f.getName());
            Configuration config = getConfiguration(toConfigKey(f), pid[0], pid[1]);
    
            Dictionary props = config.getProperties();
            Hashtable old = props != null ? new Hashtable(new DictionaryAsMap(props)) : null;
            if (old != null) {
            	old.remove( DirectoryWatcher.FILENAME );
            	old.remove( Constants.SERVICE_PID );
            	old.remove( ConfigurationAdmin.SERVICE_FACTORYPID );
            	old.remove( SERVICE_FACTORY_PID );
            }
    
            if( !ht.equals( old ) )
            {
                ht.put(DirectoryWatcher.FILENAME, toConfigKey(f));
                if (pid != null && pid[1] != null) {
                    ht.put(SERVICE_FACTORY_PID, pid[1]);
                }
                if (config.getBundleLocation() != null)
                {
                    config.setBundleLocation(null);
                }
                if (pid[1] == null) {
                    logger.info("Loaded changed configuration for {} from {}", pid[0], f.getName());
                } else {
                    logger.info("Loaded changed configuration for {} {} from {}", 
                            new Object[] {pid[0], pid[1], f.getName()});
                }
                config.update(ht);
                updated = true;
            }
            else
            {
                logger.debug("File contents of configuration for {} from {} has not changed.", pid[1], f);
                updated = false;
            }
        } catch (Exception ex) {
            logger.warn("Loading configuration file " + f + " failed ", ex);
        }
        return updated;
    }

    /**
     * Remove the configuration.
     *
     * @param f
     *            File where the configuration in whas defined.
     * @return
     * @throws Exception
     */
    boolean deleteConfig(File f) throws Exception
    {
        String pid[] = parsePid(f.getName());
        Configuration config = getConfiguration(toConfigKey(f), pid[0], pid[1]);
        config.delete();
        return true;
    }

    String toConfigKey(File f) {
        return f.getAbsoluteFile().toURI().toString();
    }

    File fromConfigKey(String key) {
        return new File(URI.create(key));
    }

    String[] parsePid(String path)
    {
        String pid = path.substring(0, path.lastIndexOf('.'));
        int n = pid.indexOf('-');
        if (n > 0)
        {
            String factoryPid = pid.substring(n + 1);
            pid = pid.substring(0, n);
            pid = qualifyPid(pid);
            logger.info("Configuring service PID {} factory PID {}", pid, factoryPid);
            return new String[]
                {
                    pid, factoryPid
                };
        }
        else
        {
            pid = qualifyPid(pid);
            return new String[]
                {
                    pid, null
                };
        }
    }
    
    /**
     * Prefixes unqualified PIDs with the default RDN qualifier
     * I.e. file names can be unqualified and will be prefixed
     * with the default. 
     * Configuring services with PIDs that are not qualified 
     * by org. or com. is currently not supported.
     */
    String qualifyPid(String fileNamePid) {
        String qualifiedPid = fileNamePid;
        // Prefix unqualified pid names with the default.
        if (!(fileNamePid.startsWith("org.") || fileNamePid.startsWith("com."))) {
            qualifiedPid = DEFAULT_SERVICE_RDN_PREFIX + fileNamePid;
        }
        return qualifiedPid;
    }

    Configuration getConfiguration(String fileName, String pid, String factoryPid)
        throws Exception
    {
        Configuration oldConfiguration = findExistingConfiguration(fileName);
        if (oldConfiguration != null)
        {
            logger.debug("Updating configuration from " + pid + (factoryPid == null ? "" : "-" + factoryPid) + ".json");
            return oldConfiguration;
        }
        else
        {
            Configuration newConfiguration;
            if (factoryPid != null)
            {
                newConfiguration = getConfigurationAdmin().createFactoryConfiguration(pid, null);
            }
            else
            {
                newConfiguration = getConfigurationAdmin().getConfiguration(pid, null);
            }
            return newConfiguration;
        }
    }

    Configuration findExistingConfiguration(String fileName) throws Exception
    {
        String filter = "(" + DirectoryWatcher.FILENAME + "=" + fileName + ")";
        Configuration[] configurations = getConfigurationAdmin().listConfigurations(filter);
        if (configurations != null && configurations.length > 0)
        {
            return configurations[0];
        }
        else
        {
            return null;
        }
    }

}

/**
 * A wrapper around a dictionary access it as a Map
 */
class DictionaryAsMap<U, V> extends AbstractMap<U, V>
{

    private Dictionary<U, V> dict;

    public DictionaryAsMap(Dictionary<U, V> dict)
    {
        this.dict = dict;
    }

    @Override
    public Set<Entry<U, V>> entrySet()
    {
        return new AbstractSet<Entry<U, V>>()
        {
            @Override
            public Iterator<Entry<U, V>> iterator()
            {
                final Enumeration<U> e = dict.keys();
                return new Iterator<Entry<U, V>>()
                {
                    private U key;
                    public boolean hasNext()
                    {
                        return e.hasMoreElements();
                    }

                    public Entry<U, V> next()
                    {
                        key = e.nextElement();
                        return new KeyEntry(key);
                    }

                    public void remove()
                    {
                        if (key == null)
                        {
                            throw new IllegalStateException();
                        }
                        dict.remove(key);
                    }
                };
            }

            @Override
            public int size()
            {
                return dict.size();
            }
        };
    }

    @Override
    public V put(U key, V value) {
        return dict.put(key, value);
    }

    class KeyEntry implements Map.Entry<U,V> {

        private final U key;

        KeyEntry(U key) {
            this.key = key;
        }

        public U getKey() {
            return key;
        }

        public V getValue() {
            return dict.get(key);
        }

        public V setValue(V value) {
            return DictionaryAsMap.this.put(key, value);
        }
    }

}