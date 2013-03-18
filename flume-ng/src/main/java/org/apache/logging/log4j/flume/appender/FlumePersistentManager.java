/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.flume.appender;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.StatsConfig;
import org.apache.flume.event.SimpleEvent;
import org.apache.logging.log4j.LoggingException;
import org.apache.logging.log4j.core.appender.ManagerFactory;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.helpers.FileUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 *
 */
public class FlumePersistentManager extends FlumeAvroManager {

    public static final String PASSWORD = "password";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String SHUTDOWN = "Shutdown";

    private static final String DEFAULT_DATA_DIR = ".log4j/flumeData";

    private static ManagerFactory factory = new  BDBManagerFactory();

    private Database database;

    private final WriterThread worker;

    private final int reconnectionDelay;

    private final LinkedBlockingQueue<byte []> queue = new LinkedBlockingQueue<byte[]>();

    private final SecretKey secretKey;

    /**
     The default reconnection delay (5 minutes).
     */
    public static final int DEFAULT_DELAY = 1000 * 60 * 5;

    /**
     * Constructor
     * @param name The unique name of this manager.
     * @param agents An array of Agents.
     * @param batchSize The number of events to include in a batch.
     * @param database The database to write to.
     */
    protected FlumePersistentManager(final String name, final String shortName, final Agent[] agents,
                                     final int batchSize, final int reconnectionDelay, final Database database,
                                     SecretKey secretKey) {
        super(name, shortName, agents, batchSize);
        this.database = database;
        this.worker = new WriterThread(database, this, queue, secretKey);
        this.worker.start();
        this.reconnectionDelay = reconnectionDelay <= 0 ? DEFAULT_DELAY : reconnectionDelay;
        this.secretKey = secretKey;
    }


    /**
     * Returns a FlumeAvroManager.
     * @param name The name of the manager.
     * @param agents The agents to use.
     * @param batchSize The number of events to include in a batch.
     * @return A FlumeAvroManager.
     */
    public static FlumePersistentManager getManager(final String name, final Agent[] agents, Property[] properties,
                                                    int batchSize, final int reconnectionDelay, final String dataDir) {
        if (agents == null || agents.length == 0) {
            throw new IllegalArgumentException("At least one agent is required");
        }

        if (batchSize <= 0) {
            batchSize = 1;
        }
        String dataDirectory = dataDir == null || dataDir.length() == 0 ? DEFAULT_DATA_DIR : dataDir;

        final StringBuilder sb = new StringBuilder("FlumeKrati[");
        boolean first = true;
        for (final Agent agent : agents) {
            if (!first) {
                sb.append(",");
            }
            sb.append(agent.getHost()).append(":").append(agent.getPort());
            first = false;
        }
        sb.append("]");
        sb.append(" ").append(dataDirectory);
        return (FlumePersistentManager) getManager(sb.toString(), factory, new FactoryData(name, agents, batchSize,
            reconnectionDelay, dataDir, properties));
    }

    @Override
    public synchronized void send(final SimpleEvent event, int delay, int retries)  {
        if (worker.isShutdown()) {
            throw new LoggingException("Unable to record event");
        }

        Map<String, String> headers = event.getHeaders();
        byte[] keyData = headers.get(FlumeEvent.GUID).getBytes(UTF8);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream daos = new DataOutputStream(baos);
            daos.writeInt(event.getBody().length);
            daos.write(event.getBody(), 0, event.getBody().length);
            daos.writeInt(event.getHeaders().size());
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                daos.writeUTF(entry.getKey());
                daos.writeUTF(entry.getValue());
            }
            byte[] eventData = baos.toByteArray();
            if (secretKey != null) {
                Cipher cipher = Cipher.getInstance("AES");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                eventData = cipher.doFinal(eventData);
            }
            final DatabaseEntry key = new DatabaseEntry(keyData);
            final DatabaseEntry data = new DatabaseEntry(eventData);
            database.put(null, key, data);
            queue.add(keyData);
        } catch (Exception ex) {
            throw new LoggingException("Exception occurred writing log event", ex);
        }
    }

    @Override
    protected void releaseSub() {
        worker.shutdown();
        try {
            worker.join();
        } catch (InterruptedException ex) {
            LOGGER.debug("Interrupted while waiting for worker to complete");
        }
        try {
            LOGGER.debug("FlumePersistenceManager dataset status: {}", database.getStats(new StatsConfig()));
            database.close();
        } catch (final Exception ex) {
            LOGGER.warn("Failed to close database", ex);
        }
        super.releaseSub();
    }

    private void doSend(final SimpleEvent event) {
        LOGGER.debug("Sending event to Flume");
        super.send(event, 1, 1);
    }

    /**
     * Factory data.
     */
    private static class FactoryData {
        private final String name;
        private final Agent[] agents;
        private final int batchSize;
        private final String dataDir;
        private final int reconnectionDelay;
        private final Property[] properties;

        /**
         * Constructor.
         * @param name The name of the Appender.
         * @param agents The agents.
         * @param batchSize The number of events to include in a batch.
         * @param dataDir The directory for data.
         */
        public FactoryData(final String name, final Agent[] agents, final int batchSize, final int reconnectionDelay,
                           final String dataDir, final Property[] properties) {
            this.name = name;
            this.agents = agents;
            this.batchSize = batchSize;
            this.dataDir = dataDir;
            this.reconnectionDelay = reconnectionDelay;
            this.properties = properties;
        }
    }

    /**
     * Avro Manager Factory.
     */
    private static class BDBManagerFactory implements ManagerFactory<FlumePersistentManager, FactoryData> {

        /**
         * Create the FlumeKratiManager.
         * @param name The name of the entity to manage.
         * @param data The data required to create the entity.
         * @return The FlumeKratiManager.
         */
        public FlumePersistentManager createManager(final String name, final FactoryData data) {
            SecretKey secretKey = null;
            byte[] salt;

            Database database;

            Map<String, String> properties = new HashMap<String, String>();
            if (data.properties != null) {
                for (Property property : data.properties) {
                    properties.put(property.getName(), property.getValue());
                }
            }

            try {

                File dir = new File(data.dataDir);
                FileUtils.mkdir(dir, true);
                final EnvironmentConfig dbEnvConfig = new EnvironmentConfig();
                dbEnvConfig.setTransactional(false);
                dbEnvConfig.setAllowCreate(true);
                final Environment environment = new Environment(dir, dbEnvConfig);
                final DatabaseConfig dbConfig = new DatabaseConfig();
                dbConfig.setTransactional(false);
                dbConfig.setAllowCreate(true);
                database = environment.openDatabase(null, name, dbConfig);
            } catch (final Exception ex) {
                LOGGER.error("Could not create FlumePersistentManager", ex);
                return null;
            }

            try {
                if (properties.containsKey(PASSWORD)) {
                    String password = properties.get(PASSWORD);
                    salt = new byte[20];
                    File saltFile = new File(data.dataDir + "/salt.dat");
                    boolean needSalt = true;
                    if (saltFile.exists()) {
                        FileInputStream fis = new FileInputStream(saltFile);
                        if (fis.read(salt) == 20) {
                            needSalt = false;
                        }
                        fis.close();
                    }
                    if (needSalt) {
                        Random r = new SecureRandom();
                        r.nextBytes(salt);
                        FileOutputStream fos = new FileOutputStream(saltFile);
                        fos.write(salt);
                        fos.close();
                    }
                    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
                    KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
                    SecretKey tmp = factory.generateSecret(spec);
                    secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");
                }
                return new FlumePersistentManager(name, data.name, data.agents, data.batchSize, data.reconnectionDelay,
                    database, secretKey);
            } catch (Exception ex) {
                LOGGER.warn("Error setting up encryption - encryption will be disabled", ex);

            }
            return null;
        }
    }

    private static class WriterThread extends Thread  {
        private volatile boolean shutdown = false;
        private final Database database;
        private final FlumePersistentManager manager;
        private final LinkedBlockingQueue<byte[]> queue;
        private final SecretKey secretKey;

        public WriterThread(Database database, FlumePersistentManager manager, LinkedBlockingQueue<byte[]> queue,
                            SecretKey secretKey) {
            this.database = database;
            this.manager = manager;
            this.queue = queue;
            this.secretKey = secretKey;
        }

        public void shutdown() {
            this.shutdown = true;
            if (queue.size() == 0) {
                queue.add(SHUTDOWN.getBytes(UTF8));
            }
        }

        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public void run() {
            LOGGER.trace("WriterThread started");
            while (!shutdown) {
                try {
                    boolean errors = false;
                    final DatabaseEntry key = new DatabaseEntry();
                    final DatabaseEntry data = new DatabaseEntry();
                    final Cursor cursor = database.openCursor(null, null);
                    try {
                        queue.clear();
                        OperationStatus status;
                        try {
                            status = cursor.getFirst(key, data, LockMode.RMW);

                            while (status == OperationStatus.SUCCESS) {
                                SimpleEvent event = new SimpleEvent();
                                try {
                                    byte[] eventData = data.getData();
                                    if (secretKey != null) {
                                        Cipher cipher = Cipher.getInstance("AES");
                                        cipher.init(Cipher.DECRYPT_MODE, secretKey);
                                        eventData = cipher.doFinal(eventData);
                                    }
                                    ByteArrayInputStream bais = new ByteArrayInputStream(eventData);
                                    DataInputStream dais = new DataInputStream(bais);
                                    int length = dais.readInt();
                                    byte[] bytes = new byte[length];
                                    dais.read(bytes, 0, length);
                                    event.setBody(bytes);
                                    length = dais.readInt();
                                    Map<String, String> map = new HashMap<String, String>(length);
                                    for (int i = 0; i < length; ++i) {
                                        String headerKey = dais.readUTF();
                                        String value = dais.readUTF();
                                        map.put(headerKey, value);
                                    }
                                    event.setHeaders(map);
                                } catch (Exception ex) {
                                    errors = true;
                                    LOGGER.error("Error retrieving event", ex);
                                    continue;
                                }
                                try {
                                    manager.doSend(event);
                                } catch (Exception ioe) {
                                    errors = true;
                                    LOGGER.error("Error sending event", ioe);
                                    break;
                                }
                                if (!errors) {
                                    try {
                                        cursor.delete();
                                    } catch (Exception ex) {
                                        LOGGER.error("Unable to delete event", ex);
                                    }
                                }
                                status = cursor.getNext(key, data, LockMode.RMW);
                            }
                        } catch (Exception ex) {
                            LOGGER.error("Error reading database", ex);
                            shutdown = true;
                            break;
                        }

                    } finally {
                        cursor.close();
                    }
                    if (errors) {
                        Thread.sleep(manager.reconnectionDelay);
                        continue;
                    }
                } catch (Exception ex) {
                    LOGGER.warn("WriterThread encountered an exception. Continuing.", ex);
                }
                try {
                    if (database.count() > 0) {
                        continue;
                    }
                    queue.take();
                    LOGGER.debug("WriterThread notified of work");
                } catch (InterruptedException ie) {
                    LOGGER.warn("WriterThread interrupted, continuing");
                } catch (Exception ex) {
                    LOGGER.error("WriterThread encountered an exception waiting for work", ex);
                    break;
                }
            }
            LOGGER.trace("WriterThread exiting");
        }

    }
}
