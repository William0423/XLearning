package net.qihoo.xlearning.client;

import net.qihoo.xlearning.AM.ApplicationMaster;
import net.qihoo.xlearning.api.ApplicationMessageProtocol;
import net.qihoo.xlearning.api.XLearningConstants;
import net.qihoo.xlearning.common.LogType;
import net.qihoo.xlearning.common.Message;
import net.qihoo.xlearning.common.exceptions.RequestOverLimitException;
import net.qihoo.xlearning.conf.XLearningConfiguration;
import net.qihoo.xlearning.util.Utilities;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.OutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.*;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {

  private static final Log LOG = LogFactory.getLog(Client.class);
  private ClientArguments clientArguments;
  private final XLearningConfiguration conf;
  private YarnClient yarnClient;
  private YarnClientApplication newAPP;
  private ApplicationMessageProtocol xlearningClient;
  private transient AtomicBoolean isRunning;
  private StringBuffer appFilesRemotePath;
  private StringBuffer appLibJarsRemotePath;
  private ApplicationId applicationId;
  private final FileSystem dfs;
  private final ConcurrentHashMap<String, String> inputPaths;
  private final ConcurrentHashMap<String, String> outputPaths;
  private static FsPermission JOB_FILE_PERMISSION;

  private Client(String[] args) throws IOException, ParseException, ClassNotFoundException {
    this.conf = new XLearningConfiguration();

    this.dfs = FileSystem.get(conf);

    // 对命令行参数进行解析：https://commons.apache.org/proper/commons-cli/usage.html
    this.clientArguments = new ClientArguments(args);

    this.isRunning = new AtomicBoolean(false);
    this.appFilesRemotePath = new StringBuffer(1000);
    this.appLibJarsRemotePath = new StringBuffer(1000);

    this.inputPaths = new ConcurrentHashMap<>();
    this.outputPaths = new ConcurrentHashMap<>();

    JOB_FILE_PERMISSION = FsPermission.createImmutable((short) 0644);
  }

  private void init() throws IOException, YarnException {
    String appSubmitterUserName = System.getenv(ApplicationConstants.Environment.USER.name());
    if (conf.get("hadoop.job.ugi") == null) {
      UserGroupInformation ugi = UserGroupInformation.createRemoteUser(appSubmitterUserName);
      conf.set("hadoop.job.ugi", ugi.getUserName() + "," + ugi.getUserName());
    }

    conf.set(XLearningConfiguration.XLEARNING_AM_MEMORY, String.valueOf(clientArguments.amMem));
    conf.set(XLearningConfiguration.XLEARNING_AM_CORES, String.valueOf(clientArguments.amCores));
    conf.set(XLearningConfiguration.XLEARNING_WORKER_MEMORY, String.valueOf(clientArguments.workerMemory));
    conf.set(XLearningConfiguration.XLEARNING_WORKER_VCORES, String.valueOf(clientArguments.workerVCores));
    conf.set(XLearningConfiguration.XLEARNING_WORKER_NUM, String.valueOf(clientArguments.workerNum));
    conf.set(XLearningConfiguration.XLEARNING_PS_MEMORY, String.valueOf(clientArguments.psMemory));
    conf.set(XLearningConfiguration.XLEARNING_PS_VCORES, String.valueOf(clientArguments.psVCores));
    conf.set(XLearningConfiguration.XLEARNING_PS_NUM, String.valueOf(clientArguments.psNum));
    conf.set(XLearningConfiguration.XLEARNING_APP_PRIORITY, String.valueOf(clientArguments.priority));
    conf.setBoolean(XLearningConfiguration.XLEARNING_USER_CLASSPATH_FIRST, clientArguments.userClasspathFirst);
    conf.set(XLearningConfiguration.XLEARNING_TF_BOARD_WORKER_INDEX, String.valueOf(clientArguments.boardIndex));
    conf.set(XLearningConfiguration.XLEARNING_TF_BOARD_RELOAD_INTERVAL, String.valueOf(clientArguments.boardReloadInterval));
    conf.set(XLearningConfiguration.XLEARNING_TF_BOARD_ENABLE, String.valueOf(clientArguments.boardEnable));
    conf.set(XLearningConfiguration.XLEARNING_TF_BOARD_LOG_DIR, clientArguments.boardLogDir);
    conf.set(XLearningConfiguration.XLEARNING_TF_BOARD_HISTORY_DIR, clientArguments.boardHistoryDir);
    conf.set(XLearningConfiguration.XLEARNING_BOARD_MODELPB, clientArguments.boardModelPB);
    conf.set(XLearningConfiguration.XLEARNING_BOARD_CACHE_TIMEOUT, String.valueOf(clientArguments.boardCacheTimeout));
    conf.set(XLearningConfiguration.XLEARNING_INPUT_STRATEGY, clientArguments.inputStrategy);
    conf.set(XLearningConfiguration.XLEARNING_OUTPUT_STRATEGY, clientArguments.outputStrategy);
    conf.setBoolean(XLearningConfiguration.XLEARNING_INPUTFILE_RENAME, clientArguments.isRenameInputFile);
    conf.setBoolean(XLearningConfiguration.XLEARNING_INPUT_STREAM_SHUFFLE, clientArguments.inputStreamShuffle);
    conf.setClass(XLearningConfiguration.XLEARNING_INPUTF0RMAT_CLASS, clientArguments.inputFormatClass, InputFormat.class);
    conf.setClass(XLearningConfiguration.XLEARNING_OUTPUTFORMAT_CLASS, clientArguments.outputFormatClass, OutputFormat.class);
    conf.set(XLearningConfiguration.XLEARNING_STREAM_EPOCH, String.valueOf(clientArguments.streamEpoch));

    if (clientArguments.queue == null || clientArguments.queue.equals("")) {
      clientArguments.queue = appSubmitterUserName;
    }
    conf.set(XLearningConfiguration.XLEARNING_APP_QUEUE, clientArguments.queue);

    if (clientArguments.confs != null) {
      setConf();
    }

    if ("TENSORFLOW".equals(clientArguments.appType)) {
      if (conf.getInt(XLearningConfiguration.XLEARNING_PS_NUM, XLearningConfiguration.DEFAULT_XLEARNING_PS_NUM) == 0) {
        conf.setBoolean(XLearningConfiguration.XLEARNING_TF_MODE_SINGLE, true);
      }
    }

    if ("MXNET".equals(clientArguments.appType)) {
      if (conf.getInt(XLearningConfiguration.XLEARNING_PS_NUM, XLearningConfiguration.DEFAULT_XLEARNING_PS_NUM) == 0) {
        conf.setBoolean(XLearningConfiguration.XLEARNING_MXNET_MODE_SINGLE, true);
      }
    }

    if (conf.getInt(XLearningConfiguration.XLEARNING_WORKER_NUM, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_NUM) == 1) {
      conf.setInt(XLearningConfiguration.XLEARNING_TF_BOARD_WORKER_INDEX, 0);
    }

    if (conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_LOG_DIR, XLearningConfiguration.DEFAULT_XLEARNING_TF_BOARD_LOG_DIR).indexOf("/") == 0) {
      Path tf_board_log_dir = new Path(conf.get("fs.defaultFS"), conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_LOG_DIR));
      conf.set(XLearningConfiguration.XLEARNING_TF_BOARD_LOG_DIR, tf_board_log_dir.toString());
    }
    if ((conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_LOG_DIR).indexOf("hdfs") == 0) && (!"TENSORFLOW".equals(clientArguments.appType))) {
      LOG.warn("VisualDL not support the hdfs path for logdir. Please ensure the logdir setting is right.");
    }

      // hadoop Yarn api编程
      yarnClient = YarnClient.createYarnClient();
      // 构造一个Yarn客户端句柄并初始化
      yarnClient.init(conf);
      // 启动Yarn
      yarnClient.start();
      // 4\\ : 需要的NM数量
      LOG.info("Requesting a new application from cluster with " + yarnClient.getYarnClusterMetrics().getNumNodeManagers() + " NodeManagers");
      // 获取一个新的application id
      newAPP = yarnClient.createApplication();
  }

  private static void showWelcome() {
    System.err.println("Welcome to\n " +
            "\t__   ___                           _\n" +
            "\t\\ \\ / / |                         (_) \n" +
            "\t \\ V /| |     ___  __ _ _ __ _ __  _ _ __   __ _ \n" +
            "\t  > < | |    / _ \\/ _` | '__| '_ \\| | '_ \\ / _` |\n" +
            "\t / . \\| |___|  __/ (_| | |  | | | | | | | | (_| |\n" +
            "\t/_/ \\_\\______\\___|\\__,_|_|  |_| |_|_|_| |_|\\__, |\n" +
            "\t                                            __/ |\n" +
            "\t                                           |___/ \n"
    );
  }

  private void setConf() {
    Enumeration<String> confSet = (Enumeration<String>) clientArguments.confs.propertyNames();
    while (confSet.hasMoreElements()) {
      String confArg = confSet.nextElement();
      conf.set(confArg, clientArguments.confs.getProperty(confArg));
    }
  }

  @SuppressWarnings("unchecked")
  private void assignOutput() throws IOException {
    Enumeration<String> outputs = (Enumeration<String>) clientArguments.outputs.propertyNames();
    while (outputs.hasMoreElements()) {
      String outputRemote = outputs.nextElement();
      String outputLocal = clientArguments.outputs.getProperty(outputRemote);
      if (outputLocal.equals("true")) {
        outputLocal = conf.get(XLearningConfiguration.XLEARNING_OUTPUT_LOCAL_DIR, XLearningConfiguration.DEFAULT_XLEARNING_OUTPUT_LOCAL_DIR);
        LOG.info("Remote output path: " + outputRemote + " not defined the local output path. Default path: output.");
      }
      Path path = new Path(outputRemote);
      if (path.getFileSystem(conf).exists(path)) {
        throw new IOException("Output path " + path + " already existed!");
      }
      if (outputPaths.containsKey(outputLocal)) {
        outputPaths.put(outputLocal, outputPaths.get(outputLocal) + "," + outputRemote);
      } else {
        outputPaths.put(outputLocal, outputRemote);
      }
      // 6\\打印本地或者hdfs的输出路径
      LOG.info("Local output path: " + outputLocal + " and remote output path: " + outputRemote);
    }
  }

  @SuppressWarnings("unchecked")
  private void assignInput() throws IOException {
    Enumeration<String> inputs = (Enumeration<String>) clientArguments.inputs.propertyNames();

    while (inputs.hasMoreElements()) {
      String inputRemote = inputs.nextElement();

      // 远程文件路径和本地文件路径是key-value的形式
      String inputLocal = clientArguments.inputs.getProperty(inputRemote);
      if (inputLocal.equals("true")) {
        inputLocal = "input";
      }

      for (String pathdir : StringUtils.split(inputRemote, ",")) {
        Path path = new Path(pathdir);
        if (!path.getFileSystem(conf).exists(path)) {
          throw new IOException("Input path " + path + " not existed!");
        }
      }

      // HashMap的形式，本地文件名相同，以逗号作分隔符追加对应的远程文件名
      if (inputPaths.containsKey(inputLocal)) {
        inputPaths.put(inputLocal, inputPaths.get(inputLocal) + "," + inputRemote);
      } else {
        inputPaths.put(inputLocal, inputRemote);
      }

      // 5\\打印本地input的路径，或者打印hdfs的input路径："Local input path: data and remote input path: /tmp/data/tensorflow
      LOG.info("Local input path: " + inputLocal + " and remote input path: " + inputRemote);
    }
  }

  private static ApplicationReport getApplicationReport(ApplicationId appId, YarnClient yarnClient) throws YarnException, IOException {
    return yarnClient.getApplicationReport(appId);
  }

  private static ApplicationMessageProtocol getAppMessageHandler(YarnConfiguration conf, String appMasterAddress, int appMasterPort) throws IOException {

    ApplicationMessageProtocol appMessageHandler = null;

    if (!StringUtils.isBlank(appMasterAddress) && !appMasterAddress.equalsIgnoreCase("N/A")) {
      InetSocketAddress addr = new InetSocketAddress(appMasterAddress, appMasterPort);
      appMessageHandler = RPC.getProxy(ApplicationMessageProtocol.class, ApplicationMessageProtocol.versionID, addr, conf);
    }

    return appMessageHandler;
  }

  /**
   * 检查任务运行时设置的参数是否在资源允许的范围内
   * @param conf
   * @param newApplication
   */
  private void checkArguments(XLearningConfiguration conf, GetNewApplicationResponse newApplication) {
    int maxMem = newApplication.getMaximumResourceCapability().getMemory();
    // 8\\
    LOG.info("Max mem capability of resources in this cluster " + maxMem);
    int maxVCores = newApplication.getMaximumResourceCapability().getVirtualCores();
    // 9\\ 最大核数量
    LOG.info("Max vcores capability of resources in this cluster " + maxVCores);

    int amMem = conf.getInt(XLearningConfiguration.XLEARNING_AM_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_AM_MEMORY);
    int amCores = conf.getInt(XLearningConfiguration.XLEARNING_AM_CORES, XLearningConfiguration.DEFAULT_XLEARNING_AM_CORES);
    // 如果一台am需要的内存大于集群需要的总内存，抛出错误。
    if (amMem > maxMem) {
      throw new RequestOverLimitException("AM memory requested " + amMem +
              " above the max threshold of yarn cluster " + maxMem);
    }
    if (amMem <= 0) {
      throw new IllegalArgumentException(
              "Invalid memory specified for application master, exiting."
                      + " Specified memory=" + amMem);
    }
    // 10\\
    LOG.info("Apply for am Memory " + amMem + "M");

    if (amCores > maxVCores) {
      throw new RequestOverLimitException("am vcores requested " + amCores +
              " above the max threshold of yarn cluster " + maxVCores);
    }
    if (amCores <= 0) {
      throw new IllegalArgumentException(
              "Invalid vcores specified for am, exiting."
                      + "Specified vcores=" + amCores);
    }

    // 11\\
    LOG.info("Apply for am vcores " + amCores);

    int workerNum = conf.getInt(XLearningConfiguration.XLEARNING_WORKER_NUM, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_NUM);
    int workerMemory = conf.getInt(XLearningConfiguration.XLEARNING_WORKER_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_MEMORY);
    int workerVcores = conf.getInt(XLearningConfiguration.XLEARNING_WORKER_VCORES, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_VCORES);
    if (workerNum < 1) {
      throw new IllegalArgumentException(
              "Invalid no. of worker specified, exiting."
                      + " Specified container number=" + workerNum);
    }
    // 12\\
    LOG.info("Apply for worker number " + workerNum);


    if (workerMemory > maxMem) {
      throw new RequestOverLimitException("Worker memory requested " + workerMemory +
              " above the max threshold of yarn cluster " + maxMem);
    }
    if (workerMemory <= 0) {
      throw new IllegalArgumentException(
              "Invalid memory specified for worker, exiting."
                      + "Specified memory=" + workerMemory);
    }
    // 13\\
    LOG.info("Apply for worker Memory " + workerMemory + "M");


    if (workerVcores > maxVCores) {
      throw new RequestOverLimitException("Worker vcores requested " + workerVcores +
              " above the max threshold of yarn cluster " + maxVCores);
    }
    if (workerVcores <= 0) {
      throw new IllegalArgumentException(
              "Invalid vcores specified for worker, exiting."
                      + "Specified vcores=" + workerVcores);
    }
    // 14\\
    LOG.info("Apply for worker vcores " + workerVcores);

    if ("TENSORFLOW".equals(clientArguments.appType) || "MXNET".equals(clientArguments.appType)) {
      Boolean single;
      if ("TENSORFLOW".equals(clientArguments.appType)) {
        single = conf.getBoolean(XLearningConfiguration.XLEARNING_TF_MODE_SINGLE, XLearningConfiguration.DEFAULT_XLEARNING_TF_MODE_SINGLE);
      } else {
        single = conf.getBoolean(XLearningConfiguration.XLEARNING_MXNET_MODE_SINGLE, XLearningConfiguration.DEFAULT_XLEARNING_MXNET_MODE_SINGLE);
      }
      int psNum = conf.getInt(XLearningConfiguration.XLEARNING_PS_NUM, XLearningConfiguration.DEFAULT_XLEARNING_PS_NUM);
      if (psNum < 0) {
        throw new IllegalArgumentException(
                "Invalid no. of ps specified, exiting."
                        + " Specified container number=" + psNum);
      }
      LOG.info("Apply for ps number " + psNum);
      if (!single) {
        int psMemory = conf.getInt(XLearningConfiguration.XLEARNING_PS_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_PS_MEMORY);
        int psVcores = conf.getInt(XLearningConfiguration.XLEARNING_PS_VCORES, XLearningConfiguration.DEFAULT_XLEARNING_PS_VCORES);
        if (psMemory > maxMem) {
          throw new RequestOverLimitException("ps memory requested " + psMemory +
                  " above the max threshold of yarn cluster " + maxMem);
        }
        if (psMemory <= 0) {
          throw new IllegalArgumentException(
                  "Invalid memory specified for ps, exiting."
                          + "Specified memory=" + psMemory);
        }
        LOG.info("Apply for ps Memory " + psMemory + "M");
        if (psVcores > maxVCores) {
          throw new RequestOverLimitException("ps vcores requested " + psVcores +
                  " above the max threshold of yarn cluster " + maxVCores);
        }
        if (psVcores <= 0) {
          throw new IllegalArgumentException(
                  "Invalid vcores specified for ps, exiting."
                          + "Specified vcores=" + psVcores);
        }
        LOG.info("Apply for ps vcores " + psVcores);
      }
      int limitNode = conf.getInt(XLearningConfiguration.XLEARNING_EXECUTE_NODE_LIMIT, XLearningConfiguration.DEFAULT_XLEARNING_EXECUTENODE_LIMIT);
      if (workerNum + psNum > limitNode) {
        throw new RequestOverLimitException("Container num requested over the limit " + limitNode);
      }
    }
  }

  private boolean submitAndMonitor() throws IOException, YarnException {
    if (clientArguments.inputs != null) {
      assignInput();
    }

    if (clientArguments.outputs != null) {
      assignOutput();
    }

    if (clientArguments.xlearningCacheFiles != null) {
      String[] cacheFiles = StringUtils.split(clientArguments.xlearningCacheFiles, ",");
      for (String path : cacheFiles) {
        Path pathRemote;
        if (path.contains("#")) {
          String[] paths = StringUtils.split(path, "#");
          if (paths.length != 2) {
            throw new RuntimeException("Error cacheFile path format " + path);
          }
          pathRemote = new Path(paths[0]);
        } else {
          pathRemote = new Path(path);
        }

        if (!pathRemote.getFileSystem(conf).exists(pathRemote)) {
          throw new IOException("cacheFile path " + pathRemote + " not existed!");
        }

      }
    }

    if (clientArguments.xlearningCacheArchives != null) {
      String[] cacheArchives = StringUtils.split(clientArguments.xlearningCacheArchives, ",");
      for (String path : cacheArchives) {
        Path pathRemote;
        if (path.contains("#")) {
          String[] paths = StringUtils.split(path, "#");
          if (paths.length != 2) {
            throw new RuntimeException("Error cacheArchives path format " + path);
          }
          pathRemote = new Path(paths[0]);
        } else {
          pathRemote = new Path(path);
        }
        if (!pathRemote.getFileSystem(conf).exists(pathRemote)) {
          throw new IOException("cacheArchive path " + pathRemote + " not existed!");
        }
      }
    }

    /**
     * 问题：在demo的视频中，这个新建的app，在集群中需要的mem capability大小为40960，这个值是从哪里来的？
     */
    GetNewApplicationResponse newAppResponse = newAPP.getNewApplicationResponse();
    applicationId = newAppResponse.getApplicationId();
    // 7\\打印新创建app对应的id，比如：application_1513044596696_001
    LOG.info("Got new Application: " + applicationId.toString());

    Path jobConfPath = Utilities.getRemotePath(conf, applicationId, XLearningConstants.XLEARNING_JOB_CONFIGURATION);
    FSDataOutputStream out = FileSystem.create(jobConfPath.getFileSystem(conf), jobConfPath, new FsPermission(JOB_FILE_PERMISSION));

    conf.writeXml(out);
    out.close();

    Map<String, LocalResource> localResources = new HashMap<>();

    localResources.put(XLearningConstants.XLEARNING_JOB_CONFIGURATION, Utilities.createApplicationResource(dfs, jobConfPath, LocalResourceType.FILE));

    checkArguments(conf, newAppResponse);

    // 创建AM的实例对象：负责输入数据分片、启动及管理Container、执行日志保存等；
    ApplicationSubmissionContext applicationContext = newAPP.getApplicationSubmissionContext();
    /**
    * 根据官网内容：
    * The main crux of a client is to setup the ApplicationSubmissionContext which defines all the information needed by the RM to launch the AM. A client needs to set the following into the context:
    * Application info: id, name
    * Queue, priority info: Queue to which the application will be submitted, the priority to be assigned for the application.
    * User: The user submitting the application
    */
    applicationContext.setApplicationId(applicationId);
    applicationContext.setApplicationName(clientArguments.appName);
    applicationContext.setApplicationType(clientArguments.appType);

    Path appJarSrc = new Path(clientArguments.appMasterJar);
    Path appJarDst = Utilities.getRemotePath(conf, applicationId, XLearningConstants.XLEARNING_APPLICATION_JAR);
    // 15\\把XLearning的jar包复制到hdfs，并且重命名为AppMaster.jar
    LOG.info("Copying " + appJarSrc + " to remote path " + appJarDst.toString());
    dfs.copyFromLocalFile(false, true, appJarSrc, appJarDst);

    localResources.put(XLearningConstants.XLEARNING_APPLICATION_JAR, Utilities.createApplicationResource(dfs, appJarDst, LocalResourceType.FILE));

    // 16\\开始为application master设置building环境
    LOG.info("Building environments for the application master");
      // 应用的环境设置，包括训练数据存储的路径等等，
    Map<String, String> appMasterEnv = new HashMap<>();

    if (clientArguments.appType != null && !clientArguments.appType.equals("")) {
        // 设置需要运行的机器学习框架
        appMasterEnv.put(XLearningConstants.Environment.XLEARNING_APP_TYPE.toString(), clientArguments.appType);
    } else {
        appMasterEnv.put(XLearningConstants.Environment.XLEARNING_APP_TYPE.toString(), XLearningConfiguration.DEFAULT_XLEARNING_APP_TYPE.toUpperCase());
    }

    if (clientArguments.xlearningFiles != null) {
      Path[] xlearningFilesDst = new Path[clientArguments.xlearningFiles.length];
      // 17\\开始将需要用到的模型代码文件上传到hdfs
      LOG.info("Copy xlearning files from local filesystem to remote.");
      for (int i = 0; i < clientArguments.xlearningFiles.length; i++) {
        assert (!clientArguments.xlearningFiles[i].isEmpty());
        Path xlearningFilesSrc = new Path(clientArguments.xlearningFiles[i]);
        xlearningFilesDst[i] = Utilities.getRemotePath(
                conf, applicationId, new Path(clientArguments.xlearningFiles[i]).getName());
        // 18\\此处遍历打印已经上传到hdfs的模型代码文件，比如demo.py，dataDeal.py
        LOG.info("Copying " + clientArguments.xlearningFiles[i] + " to remote path " + xlearningFilesDst[i].toString());
        dfs.copyFromLocalFile(false, true, xlearningFilesSrc, xlearningFilesDst[i]);

        appFilesRemotePath.append(xlearningFilesDst[i].toUri().toString()).append(",");

      }
      appMasterEnv.put(XLearningConstants.Environment.XLEARNING_FILES_LOCATION.toString(),
              appFilesRemotePath.deleteCharAt(appFilesRemotePath.length() - 1).toString());

        // 使用MXNEXT框架，并且满足不是单机模式：
      if (clientArguments.appType.equals("MXNET") && !conf.getBoolean(XLearningConfiguration.XLEARNING_MXNET_MODE_SINGLE, XLearningConfiguration.DEFAULT_XLEARNING_MXNET_MODE_SINGLE)) {
        String appFilesRemoteLocation = appMasterEnv.get(XLearningConstants.Environment.XLEARNING_FILES_LOCATION.toString());
        String[] xlearningFiles = StringUtils.split(appFilesRemoteLocation, ",");
        for (String file : xlearningFiles) {
          Path path = new Path(file);
          localResources.put(path.getName(),
                  Utilities.createApplicationResource(path.getFileSystem(conf),
                          path,
                          LocalResourceType.FILE));
        }
      }
    }
      /**
       * 为什么要把lib种的jar传到hdfs
       */
    String libJarsClassPath = "";
    if (clientArguments.libJars != null) {
      Path[] jarFilesDst = new Path[clientArguments.libJars.length];
      LOG.info("Copy XLearning lib jars from local filesystem to remote.");
      for (int i = 0; i < clientArguments.libJars.length; i++) {
        assert (!clientArguments.libJars[i].isEmpty());
        if (!clientArguments.libJars[i].startsWith("hdfs://")) {
          Path jarFilesSrc = new Path(clientArguments.libJars[i]);
          jarFilesDst[i] = Utilities.getRemotePath(conf, applicationId, new Path(clientArguments.libJars[i]).getName());
          LOG.info("Copying " + clientArguments.libJars[i] + " to remote path " + jarFilesDst[i].toString());
          dfs.copyFromLocalFile(false, true, jarFilesSrc, jarFilesDst[i]);
          appLibJarsRemotePath.append(jarFilesDst[i].toUri().toString()).append(",");
        } else {
          Path pathRemote = new Path(clientArguments.libJars[i]);
          if (!pathRemote.getFileSystem(conf).exists(pathRemote)) {
            throw new IOException("hdfs lib jars path " + pathRemote + " not existed!");
          }
          appLibJarsRemotePath.append(clientArguments.libJars[i]).append(",");
        }
      }

      String appFilesRemoteLocation = appLibJarsRemotePath.deleteCharAt(appLibJarsRemotePath.length() - 1).toString();
      appMasterEnv.put(XLearningConstants.Environment.XLEARNING_LIBJARS_LOCATION.toString(),
              appFilesRemoteLocation);

      String[] jarFiles = StringUtils.split(appFilesRemoteLocation, ",");
      for (String file : jarFiles) {
        Path path = new Path(file);
        localResources.put(path.getName(),
                Utilities.createApplicationResource(path.getFileSystem(conf),
                        path,
                        LocalResourceType.FILE));
        libJarsClassPath += path.getName() + ":";
      }
    }

    StringBuilder classPathEnv = new StringBuilder("${CLASSPATH}:./*");
    for (String cp : conf.getStrings(XLearningConfiguration.YARN_APPLICATION_CLASSPATH,XLearningConfiguration.DEFAULT_XLEARNING_APPLICATION_CLASSPATH)) {
      classPathEnv.append(':');
      classPathEnv.append(cp.trim());
    }

    if (conf.getBoolean(XLearningConfiguration.XLEARNING_USER_CLASSPATH_FIRST, XLearningConfiguration.DEFAULT_XLEARNING_USER_CLASSPATH_FIRST)) {
      appMasterEnv.put("CLASSPATH", libJarsClassPath + classPathEnv.toString());
    } else {
      appMasterEnv.put("CLASSPATH", classPathEnv.toString() + ":" + libJarsClassPath);
    }

    appMasterEnv.put(XLearningConstants.Environment.XLEARNING_STAGING_LOCATION.toString(), Utilities.getRemotePath(conf, applicationId, "").toString());

    appMasterEnv.put(XLearningConstants.Environment.APP_JAR_LOCATION.toString(), appJarDst.toUri().toString());
    appMasterEnv.put(XLearningConstants.Environment.XLEARNING_JOB_CONF_LOCATION.toString(), jobConfPath.toString());

    if (clientArguments.launchCmd != null && !clientArguments.launchCmd.equals("")) {
      appMasterEnv.put(XLearningConstants.Environment.XLEARNING_EXEC_CMD.toString(), clientArguments.launchCmd);
    } else {
      throw new IllegalArgumentException("Invalid launch cmd for the application");
    }

    if (clientArguments.xlearningCacheFiles != null && !clientArguments.xlearningCacheFiles.equals("")) {

      appMasterEnv.put(XLearningConstants.Environment.XLEARNING_CACHE_FILE_LOCATION.toString(), clientArguments.xlearningCacheFiles);

      if ((clientArguments.appType.equals("MXNET") && !conf.getBoolean(XLearningConfiguration.XLEARNING_MXNET_MODE_SINGLE, XLearningConfiguration.DEFAULT_XLEARNING_MXNET_MODE_SINGLE))
              || clientArguments.appType.equals("DISTXGBOOST")) {
        URI defaultUri = new Path(conf.get("fs.defaultFS")).toUri();
        LOG.info("default URI is " + defaultUri.toString());
        String appCacheFilesRemoteLocation = appMasterEnv.get(XLearningConstants.Environment.XLEARNING_CACHE_FILE_LOCATION.toString());
        String[] cacheFiles = StringUtils.split(appCacheFilesRemoteLocation, ",");
        for (String path : cacheFiles) {
          Path pathRemote;
          String aliasName;
          if (path.contains("#")) {
            String[] paths = StringUtils.split(path, "#");
            if (paths.length != 2) {
              throw new RuntimeException("Error cacheFile path format " + appCacheFilesRemoteLocation);
            }
            pathRemote = new Path(paths[0]);
            aliasName = paths[1];
          } else {
            pathRemote = new Path(path);
            aliasName = pathRemote.getName();
          }
          URI pathRemoteUri = pathRemote.toUri();

          if (pathRemoteUri.getScheme() == null || pathRemoteUri.getHost() == null) {
            pathRemote = new Path(defaultUri.toString(), pathRemote.toString());
          }

          LOG.info("Cache file remote path is " + pathRemote + " and alias name is " + aliasName);
          localResources.put(aliasName,
                  Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                          pathRemote,
                          LocalResourceType.FILE));
        }
      }
    }

    if (clientArguments.xlearningCacheArchives != null && !clientArguments.xlearningCacheArchives.equals("")) {
      appMasterEnv.put(XLearningConstants.Environment.XLEARNING_CACHE_ARCHIVE_LOCATION.toString(), clientArguments.xlearningCacheArchives);
      if ((clientArguments.appType.equals("MXNET") && !conf.getBoolean(XLearningConfiguration.XLEARNING_MXNET_MODE_SINGLE, XLearningConfiguration.DEFAULT_XLEARNING_MXNET_MODE_SINGLE))
          || clientArguments.appType.equals("DISTXGBOOST")) {
        URI defaultUri = new Path(conf.get("fs.defaultFS")).toUri();
        String appCacheArchivesRemoteLocation = appMasterEnv.get(XLearningConstants.Environment.XLEARNING_CACHE_ARCHIVE_LOCATION.toString());
        String[] cacheArchives = StringUtils.split(appCacheArchivesRemoteLocation, ",");
        for (String path : cacheArchives) {
          Path pathRemote;
          String aliasName;
          if (path.contains("#")) {
            String[] paths = StringUtils.split(path, "#");
            if (paths.length != 2) {
              throw new RuntimeException("Error cacheArchive path format " + appCacheArchivesRemoteLocation);
            }
            pathRemote = new Path(paths[0]);
            aliasName = paths[1];
          } else {
            pathRemote = new Path(path);
            aliasName = pathRemote.getName();
          }
          URI pathRemoteUri = pathRemote.toUri();

          if (pathRemoteUri.getScheme() == null || pathRemoteUri.getHost() == null) {
            pathRemote = new Path(defaultUri.toString(), pathRemote.toString());
          }
          LOG.info("CacheArchive remote path is " + pathRemote + " and alias name is " + aliasName);
          localResources.put(aliasName,
                  Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                          pathRemote,
                          LocalResourceType.ARCHIVE));
        }
      }
    }

    /**
     * 问题：在哪里使用这些属性？
     */
    Set<String> inputPathKeys = inputPaths.keySet();
    StringBuilder inputLocation = new StringBuilder(1000);
    if (inputPathKeys.size() > 0) {
      // for循环重新整理数据的远程文件夹和本地文件夹，类似于：/tmp/t11,/tmp/t12#train|/tmp/t2#test
      for (String key : inputPathKeys) {
        inputLocation.append(inputPaths.get(key)).
                append("#").
                append(key).
                append("|");
      }
      appMasterEnv.put(XLearningConstants.Environment.XLEARNING_INPUTS.toString(),
              // 删除字符串最后一个字符，得到这个字符串
              inputLocation.deleteCharAt(inputLocation.length() - 1).toString());
    }

    Set<String> outputPathKeys = outputPaths.keySet();
    StringBuilder outputLocation = new StringBuilder(1000);
    if (outputPathKeys.size() > 0) {
      for (String key : outputPathKeys) {
        for (String value : StringUtils.split(outputPaths.get(key), ",")) {
          outputLocation.append(value).
                  append("#").
                  append(key).
                  append("|");
        }
      }
      appMasterEnv.put(XLearningConstants.Environment.XLEARNING_OUTPUTS.toString(),
              outputLocation.deleteCharAt(outputLocation.length() - 1).toString());
    }

    appMasterEnv.put(XLearningConstants.Environment.XLEARNING_CONTAINER_MAX_MEMORY.toString(), String.valueOf(newAppResponse.getMaximumResourceCapability().getMemory()));

    if (clientArguments.userPath != null && !clientArguments.userPath.equals("")) {
      appMasterEnv.put(XLearningConstants.Environment.USER_PATH.toString(), clientArguments.userPath);
    }

    // 19\\创建运行训练模型的java命令，这句话运行 “net.qihoo.xlearning.AM.ApplicationMaster.class”这个类，
    // 对应此处为pdf文档中"XLearning执行流程"的第七步
    LOG.info("Building application master launch command");
    List<String> appMasterArgs = new ArrayList<>(20);
    appMasterArgs.add("${JAVA_HOME}" + "/bin/java");
      // 设置默认的最大堆和最小堆，两个值相同
    appMasterArgs.add("-Xms" + conf.getInt(XLearningConfiguration.XLEARNING_AM_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_AM_MEMORY) + "m");
    appMasterArgs.add("-Xmx" + conf.getInt(XLearningConfiguration.XLEARNING_AM_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_AM_MEMORY) + "m");
    appMasterArgs.add(ApplicationMaster.class.getName());
    appMasterArgs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR
            + "/" + ApplicationConstants.STDOUT);
    appMasterArgs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR
            + "/" + ApplicationConstants.STDERR);

    StringBuilder command = new StringBuilder();
    for (String arg : appMasterArgs) {
      command.append(arg).append(" ");
    }

    // 20\\
    LOG.info("Application master launch command: " + command.toString());
    List<String> appMasterLaunchcommands = new ArrayList<>();
    appMasterLaunchcommands.add(command.toString());

    Resource capability = Records.newRecord(Resource.class);
    capability.setMemory(conf.getInt(XLearningConfiguration.XLEARNING_AM_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_AM_MEMORY));
    capability.setVirtualCores(conf.getInt(XLearningConfiguration.XLEARNING_AM_CORES, XLearningConfiguration.DEFAULT_XLEARNING_AM_CORES));

    applicationContext.setResource(capability);

    // am的Container
    ContainerLaunchContext amContainer = ContainerLaunchContext.newInstance(
            localResources, appMasterEnv, appMasterLaunchcommands, null, null, null);

    applicationContext.setAMContainerSpec(amContainer);

    Priority priority = Records.newRecord(Priority.class);
    priority.setPriority(conf.getInt(XLearningConfiguration.XLEARNING_APP_PRIORITY, XLearningConfiguration.DEFAULT_XLEARNING_APP_PRIORITY));
    applicationContext.setPriority(priority);
    applicationContext.setQueue(conf.get(XLearningConfiguration.XLEARNING_APP_QUEUE, XLearningConfiguration.DEFAULT_XLEARNING_APP_QUEUE));

    try {
      // 21\\
      LOG.info("Submitting application to ResourceManager");
      // 22\\内部有一个类会输出一句信息：此处为pdf文档中"XLearning执行流程"的第三步，通过Client.java文件实现
      // 在注册
      applicationId = yarnClient.submitApplication(applicationContext);
      isRunning.set(applicationId != null);
      if (isRunning.get()) {
        // 23\\
        LOG.info("Application submitAndMonitor succeed");
      } else {
        throw new RuntimeException("Application submitAndMonitor failed!  Application Not Running.");
      }
    } catch (YarnException e) {
      throw new RuntimeException("Application submitAndMonitor failed! Exception: " + e);
    }

    boolean isApplicationSucceed = waitCompleted();

    return isApplicationSucceed;
  }

  private boolean waitCompleted() throws IOException, YarnException {

    ApplicationReport applicationReport = getApplicationReport(applicationId, yarnClient);
    // 24\\ ApplicationReport#getTrackingUrl 设置一个客户端可以监控进度的跟踪url。
    LOG.info("The url to track the job: " + applicationReport.getTrackingUrl());

    while (true) {

      assert (applicationReport != null);

      if (xlearningClient == null && isRunning.get()) {
        // 25\\ 输出的样例： “Application report for application_1513044596696_001 (state: ACCEPTED)”
        LOG.info("Application report for " + applicationId +
                " (state: " + applicationReport.getYarnApplicationState().toString() + ")");
        xlearningClient = getAppMessageHandler(conf, applicationReport.getHost(),
                applicationReport.getRpcPort());
      }

      YarnApplicationState yarnApplicationState = applicationReport.getYarnApplicationState();
      FinalApplicationStatus finalApplicationStatus = applicationReport.getFinalApplicationStatus();
      if (YarnApplicationState.FINISHED == yarnApplicationState) {
        xlearningClient = null;
        isRunning.set(false);
        if (FinalApplicationStatus.SUCCEEDED == finalApplicationStatus) {
          // 运行成功，返回true
          return true;
        } else {

          LOG.info("Application has completed failed with YarnApplicationState=" + yarnApplicationState.toString() +
                  " and FinalApplicationStatus=" + finalApplicationStatus.toString());
          return false;
        }
      } else if (YarnApplicationState.KILLED == yarnApplicationState
              || YarnApplicationState.FAILED == yarnApplicationState) {
        xlearningClient = null;
        isRunning.set(false);
        //
        LOG.info("Application has completed with YarnApplicationState=" + yarnApplicationState.toString() +
                " and FinalApplicationStatus=" + finalApplicationStatus.toString());
        return false;
      }

      if (xlearningClient != null) {
        try {
          Message[] messages = xlearningClient.fetchApplicationMessages();
          if (messages != null && messages.length > 0) {
            for (Message message : messages) {
              if (message.getLogType() == LogType.STDERR) {
                LOG.info(message.getMessage());
              } else {
                System.out.println(message.getMessage());
              }
            }
          }
        } catch (UndeclaredThrowableException e) {
          xlearningClient = null;
          LOG.warn("Connecting to ResourceManager failed, try again later ", e);
        }
      }

      int logInterval = conf.getInt(XLearningConfiguration.XLEARNING_LOG_PULL_INTERVAL, XLearningConfiguration.DEFAULT_XLEARNING_LOG_PULL_INTERVAL);
      Utilities.sleep(logInterval);
      // 如果连接失败，重试
      applicationReport = getApplicationReport(applicationId, yarnClient);

    }

  }

  public static void main(String[] args) {
    showWelcome();
    boolean result = false;
    try {
      // 1\\
      LOG.info("Initializing Client");
      Client client = new Client(args);

      client.init();

      result = client.submitAndMonitor();
    } catch (Exception e) {
      LOG.fatal("Error running Client", e);
      System.exit(1);
    }
    if (result) {
      LOG.info("Application completed successfully");
      System.exit(0);
    }
    LOG.error("Application run failed!");
    System.exit(2);
  }
}
