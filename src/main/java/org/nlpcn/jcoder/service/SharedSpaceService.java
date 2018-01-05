package org.nlpcn.jcoder.service;

import com.alibaba.fastjson.JSONObject;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.eclipse.jetty.util.StringUtil;
import org.nlpcn.jcoder.domain.*;
import org.nlpcn.jcoder.job.CheckClusterJob;
import org.nlpcn.jcoder.job.MasterGroupListenerJob;
import org.nlpcn.jcoder.job.MasterRunTaskJob;
import org.nlpcn.jcoder.run.java.JavaRunner;
import org.nlpcn.jcoder.util.IOUtil;
import org.nlpcn.jcoder.util.MD5Util;
import org.nlpcn.jcoder.util.StaticValue;
import org.nlpcn.jcoder.util.ZKMap;
import org.nlpcn.jcoder.util.dao.ZookeeperDao;
import org.nutz.dao.Cnd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Created by Ansj on 05/12/2017.
 */
public class SharedSpaceService {

	private static final Logger LOG = LoggerFactory.getLogger(SharedSpaceService.class);
	/**
	 * 路由表
	 */
	public static final String MAPPING_PATH = StaticValue.ZK_ROOT + "/mapping";

	/**
	 * Token
	 */
	public static final String TOKEN_PATH = StaticValue.ZK_ROOT + "/token";


	/**
	 * Master
	 */
	private static final String MASTER_PATH = StaticValue.ZK_ROOT + "/master";

	/**
	 * Host
	 * /jcoder/host_group/[ipPort_groupName],[hostGroupInfo]
	 * /jcoder/host_group/[ipPort]
	 */
	public static final String HOST_GROUP_PATH = StaticValue.ZK_ROOT + "/host_group";


	/**
	 * 在线主机
	 */
	private static final String HOST_PATH = StaticValue.ZK_ROOT + "/host";

	/**
	 * group /jcoder/task/group/className.task
	 * |-resource (filePath,md5)
	 * |-lib libMap(libName,md5)
	 */
	public static final String GROUP_PATH = StaticValue.ZK_ROOT + "/group";


	/**
	 * group /jcoder/lock
	 */
	private static final String LOCK_PATH = StaticValue.ZK_ROOT + "/lock";

	/**
	 * 记录task执行成功失败的计数器
	 */
	private static final Map<Long, AtomicLong> taskSuccess = new HashMap<>();

	/**
	 * 记录task执行成功失败的计数器
	 */
	private static final Map<Long, AtomicLong> taskErr = new HashMap<>();

	private ZookeeperDao zkDao;


	/**
	 * 选举
	 */
	private LeaderLatch leader;

	/**
	 * 监听路由缓存
	 *
	 * @Example /jcoder/mapping/[groupName]/[className]/[methodName]/[hostPort]
	 */
	private TreeCache mappingCache;

	private ZKMap<Token> tokenCache;

	//缓存在线主机 key:127.0.0.1:2181_groupName HostGroup.java
	private ZKMap<HostGroup> hostGroupCache;

	/**
	 * 在线groupcache
	 */
	private PathChildrenCache groupCache;


	/**
	 * 计数器，记录task成功失败个数
	 *
	 * @param id
	 * @param success
	 */
	public void counter(Long id, boolean success) {
		if (success) {
			taskSuccess.compute(id, (k, v) -> {
				if (v == null) {
					v = new AtomicLong();
				}
				v.incrementAndGet();
				return v;
			});
		} else {
			taskErr.compute(id, (k, v) -> {
				if (v == null) {
					v = new AtomicLong();
				}
				v.incrementAndGet();
				return v;
			});
		}
	}

	/**
	 * 获得一个task成功次数
	 *
	 * @param id
	 * @return
	 */
	public long getSuccess(Long id) {
		AtomicLong atomicLong = taskSuccess.get(id);
		if (atomicLong == null) {
			return 0L;
		} else {
			return atomicLong.get();
		}
	}

	/**
	 * 获得一个task失败次数
	 *
	 * @param id
	 * @return
	 */
	public long getError(Long id) {
		AtomicLong atomicLong = taskErr.get(id);
		if (atomicLong == null) {
			return 0L;
		} else {
			return atomicLong.get();
		}
	}

	/**
	 * 获得一个token
	 *
	 * @param key
	 * @return
	 */
	protected Token getToken(String key) throws Exception {
		return tokenCache.get(key);
	}

	/**
	 * 递归查询所有子文件
	 *
	 * @param set
	 * @param path
	 * @return
	 * @throws Exception
	 */
	public void walkAllDataNode(Set<String> set, String path) throws Exception {
		try {
			List<String> children = zkDao.getZk().getChildren().forPath(path);

			if (children == null || children.size() == 0) {
				set.add(path);
			}
			for (String child : children) {
				walkAllDataNode(set, path + "/" + child);
			}
		} catch (Exception e) {
			LOG.error("walk file err: " + path);
		}
	}

	/**
	 * 注册一个token,token必须是刻一用路径描述的
	 *
	 * @param token
	 */
	protected void regToken(Token token) throws Exception {
		if (token.getToken().contains("/")) {
			throw new RuntimeException("token can not has / in name ");
		}
		tokenCache.put(token.getToken(), token);
	}

	/**
	 * 移除一个token
	 *
	 * @param key
	 * @return
	 */
	protected Token removeToken(String key) throws Exception {
		return tokenCache.remove(key);
	}


	/**
	 * 删除一个地址映射
	 */
	public void removeMapping(String groupName, String className, String methodName, String hostPort) {
		try {
			if (StringUtil.isBlank(methodName)) {
				zkDao.getZk().delete().forPath(MAPPING_PATH + "/" + groupName + "/" + className + "/" + hostPort);
			} else {
				zkDao.getZk().delete().forPath(MAPPING_PATH + "/" + groupName + "/" + className + "/" + methodName + "/" + hostPort);
			}
			LOG.error("remove mapping {}/{}/{}/{} ok", hostPort, groupName, className, methodName);
		} catch (Exception e) {
			e.printStackTrace();
			LOG.error("remove err {}/{}/{}/{} message: {}", hostPort, groupName, className, methodName, e.getMessage());
		}

	}

	/**
	 * 增加一个mapping到
	 */
	public void addMapping(String groupName, String className, String methodName) {

		StringBuilder sb = new StringBuilder(MAPPING_PATH);
		sb.append("/").append(groupName).append("/").append(className).append("/");

		if (StringUtil.isNotBlank(methodName)) {
			sb.append(methodName).append("/");
		}
		sb.append(StaticValue.getHostPort());

		String path = sb.toString();

		try {
			setData2ZK(path, new byte[0]);//TODO: 这个不是临时节点。所以需要定时清理
			LOG.info("add mapping: {} ok", path);
		} catch (Exception e) {
			LOG.error("Add mapping " + path + " err", e);
			e.printStackTrace();
		}
	}

	/**
	 * 增加一个task到集群中，
	 *
	 * @param task
	 */
	public void addTask(Task task) throws Exception {
		// /jcoder/task/group/className.task
		setData2ZK(GROUP_PATH + "/" + task.getGroupName() + "/" + task.getName(), JSONObject.toJSONBytes(task));
	}

	/**
	 * lock a path in /zookper/locak[/path]
	 *
	 * @param groupName
	 */
	public InterProcessMutex lockGroup(String groupName) {
		InterProcessMutex lock = new InterProcessMutex(zkDao.getZk(), LOCK_PATH + "/" + groupName);
		return lock;
	}

	/**
	 * 解锁一个目录并尝试删除
	 *
	 * @param lock
	 */
	public void unLockAndDelete(InterProcessMutex lock) {
		if (lock != null && lock.isAcquiredInThisProcess()) {
			try {
				lock.release(); //释放锁
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 传入路径，在路径中寻找合适运行此方法的主机
	 *
	 * @param groupName
	 * @param path
	 * @return 保护http。。。地址的
	 */
	public String host(String groupName, String path) {
		String[] split = path.split("/");
		if (split.length < 3) {
			return null;
		}

		String className = split[2];
		String methodName = null;
		if (split.length >= 4) {
			methodName = split[3];
		}

		if (StringUtil.isBlank(className)) {
			return null;
		}

		return host(groupName, className, methodName);

	}


	/**
	 * 传入一个地址，给出路由到的地址，如果返回空则为本机，未找到或其他情况也保留于本机
	 *
	 * @return
	 */
	public String host(String groupName, String className, String mehtodName) {

		Map<String, ChildData> currentChildren = null;
		if (StringUtil.isBlank(mehtodName)) {
			currentChildren = mappingCache.getCurrentChildren(MAPPING_PATH + "/" + groupName + "/" + className);
		} else {
			currentChildren = mappingCache.getCurrentChildren(MAPPING_PATH + "/" + groupName + "/" + className + "/" + mehtodName);
		}

		if (currentChildren == null || currentChildren.size() == 0) {
			return null;
		}

		List<KeyValue<String, Integer>> hosts = new ArrayList<>();

		int sum = 0;
		for (Map.Entry<String, ChildData> entry : currentChildren.entrySet()) {

			String hostPort = entry.getKey();

			HostGroup hostGroup = hostGroupCache.get(hostPort + "_" + groupName);

			if (hostGroup == null) {
				LOG.warn(HOST_GROUP_PATH + "/" + hostPort + "_" + groupName + " got null , so skip");
				continue;
			}

			Integer weight = hostGroup.getWeight();
			if (weight <= 0) {
				LOG.info(HOST_GROUP_PATH + "/" + hostPort + "_" + groupName + " weight less than zero , so skip");
				continue;
			}
			sum += weight;
			hosts.add(KeyValue.with((hostGroup.isSsl() ? "https://" : "http://") + hostPort, weight));
		}


		if (hosts.size() == 0) {
			return null;
		}

		int random = new Random().nextInt(sum);

		for (KeyValue<String, Integer> host : hosts) {
			random -= host.getValue();
			if (random < 0) {
				LOG.info("{}/{}/{} proxy to {} ", groupName, className, mehtodName, host.getKey());
				return host.getKey();
			}
		}

		LOG.info("this log impossible print !");

		return null;

	}

	/**
	 * 将数据写入到zk中
	 *
	 * @param path
	 * @param data
	 * @throws Exception
	 */
	private void setData2ZK(String path, byte[] data) throws Exception {

		LOG.info("add data to: {}, data len: {} ", path, data.length);

		boolean flag = true;
		if (zkDao.getZk().checkExists().forPath(path) == null) {
			try {
				zkDao.getZk().create().creatingParentsIfNeeded().forPath(path, data);
				flag = false;
			} catch (KeeperException.NodeExistsException e) {
				flag = true;
			}
		}

		if (flag) {
			zkDao.getZk().setData().forPath(path, data);
		}
	}

	public byte[] getData2ZK(String path) throws Exception {
		LOG.info("get data from: {} ", path);
		byte[] bytes = null;
		try {
			bytes = zkDao.getZk().getData().forPath(path);
		} catch (KeeperException.NoNodeException e) {
		}

		return bytes;
	}


	/**
	 * 将临时数据写入到zk中
	 *
	 * @param path
	 * @param data
	 * @throws Exception
	 */
	public void setData2ZKByEphemeral(String path, byte[] data, Watcher watcher) throws Exception {

		boolean flag = true;

		if (zkDao.getZk().checkExists().forPath(path) == null) {
			try {
				zkDao.getZk().create().withMode(CreateMode.EPHEMERAL).forPath(path, data);
				flag = false;
			} catch (KeeperException.NodeExistsException e) {
				flag = true;
			}
		}

		if (flag) {
			zkDao.getZk().setData().forPath(path, data);
		}

		if (watcher != null) {
			zkDao.getZk().getData().usingWatcher(watcher).forPath(path); //注册监听
		}
	}


	public SharedSpaceService init() throws Exception {

		long start = System.currentTimeMillis();
		LOG.info("shared space init");

		this.zkDao = new ZookeeperDao(StaticValue.ZK).start();

		//注册监听事件
		zkDao.getZk().getConnectionStateListenable().addListener((client, connectionState) -> {
			LOG.info("=============================" + connectionState);
			if (connectionState == ConnectionState.LOST) {
				while (true) {
					try {
						StaticValue.space().release();
						StaticValue.space().init();
						break;
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					} catch (Exception e) {
						LOG.error("reconn zk server ", e);
					}
				}
			}
		});


		/**
		 * 选举leader
		 */
		leader = new LeaderLatch(zkDao.getZk(), MASTER_PATH, StaticValue.getHostPort());
		leader.addListener(new LeaderLatchListener() {
			@Override
			public void isLeader() {
				StaticValue.setMaster(true);
				LOG.info("I am master my host is " + StaticValue.getHostPort());
				MasterRunTaskJob.startJob();
			}

			@Override
			public void notLeader() {
				StaticValue.setMaster(false);
				LOG.info("I am lost master " + StaticValue.getHostPort());
				MasterRunTaskJob.stopJob();
			}

		});
		leader.start();


		if (zkDao.getZk().checkExists().forPath(HOST_GROUP_PATH) == null) {
			zkDao.getZk().create().creatingParentsIfNeeded().forPath(HOST_GROUP_PATH);
		}


		if (zkDao.getZk().checkExists().forPath(GROUP_PATH) == null) {
			zkDao.getZk().create().creatingParentsIfNeeded().forPath(GROUP_PATH);
		}

		if (zkDao.getZk().checkExists().forPath(TOKEN_PATH) == null) {
			zkDao.getZk().create().creatingParentsIfNeeded().forPath(TOKEN_PATH);
		}

		if (zkDao.getZk().checkExists().forPath(HOST_PATH) == null) {
			zkDao.getZk().create().creatingParentsIfNeeded().forPath(HOST_PATH);
		}

		joinCluster();

		setData2ZKByEphemeral(HOST_PATH + "/" + StaticValue.getHostPort(), new byte[0], new Watcher() {
			@Override
			public void process(WatchedEvent event) {
				if (event.getType() == Watcher.Event.EventType.NodeDeleted) { //节点删除了
					try {
						LOG.info("I lost node so add it again " + event.getPath());
						setData2ZKByEphemeral(event.getPath(), new byte[0], this);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		});

		//映射信息
		mappingCache = new TreeCache(zkDao.getZk(), MAPPING_PATH).start();

		/**
		 * 监控token
		 */
		tokenCache = new ZKMap(zkDao.getZk(), TOKEN_PATH, Token.class).start();

		/**
		 * 缓存主机
		 */
		hostGroupCache = new ZKMap(zkDao.getZk(), HOST_GROUP_PATH, HostGroup.class).start();

		/**
		 * 监听各个group
		 */
		groupCache = new PathChildrenCache(zkDao.getZk(), GROUP_PATH, false);

		groupCache.getListenable().addListener((client, event) -> {
			if (event.getData() != null) {
				String path = event.getData().getPath();
				String groupName = path.substring(GROUP_PATH.length() + 1).split("/")[0];
				switch (event.getType()) {
					case CHILD_ADDED:
					case CHILD_UPDATED:
					case CHILD_REMOVED:
					default:
						if (StaticValue.isMaster()) { //如果是master检查定时任务
//TODO							MasterGroupListenerJob.addQueue(new Handler(groupName, path, event.getType()));
						}
//TODO						CheckClusterJob.changeGroup(groupName);
						break;
				}
			}
		});
		groupCache.start();


		LOG.info("shared space init ok use time {}", System.currentTimeMillis() - start);
		return this;

	}

	/**
	 * 主机关闭的时候调用,平时不调用
	 */
	public void release() throws Exception {
		LOG.info("release SharedSpace");
		Optional.of(groupCache).ifPresent(o -> closeWithoutException(o));
		Optional.of(leader).ifPresent((o) -> closeWithoutException(o));
		Optional.of(mappingCache).ifPresent((o) -> closeWithoutException(o));
		Optional.of(tokenCache).ifPresent((o) -> closeWithoutException(o));
		Optional.of(hostGroupCache).ifPresent((o) -> closeWithoutException(o));
		Optional.of(zkDao).ifPresent((o) -> closeWithoutException(o));
	}


	/**
	 * 关闭一个类且不抛出异常
	 *
	 * @param close
	 */
	private void closeWithoutException(Closeable close) {
		try {
			close.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}


	/**
	 * 加入集群,如果发生不同则记录到different中
	 */
	public Map<String, List<Different>> joinCluster() throws IOException {

		Map<String, List<Different>> result = new HashMap<>();

		List<Group> groups = StaticValue.systemDao.search(Group.class, "id");
		Collections.shuffle(groups); //因为要锁组，重新排序下防止顺序锁


		for (Group group : groups) {

			List<Different> diffs = joinCluster(group);

			result.put(group.getName(), diffs);

		}

		return result;
	}

	/**
	 * 加入刷新一个主机到集群中
	 *
	 * @param group
	 * @return
	 * @throws IOException
	 */
	public List<Different> joinCluster(Group group) throws IOException {
		List<Different> diffs = new ArrayList<>();

		String groupName = group.getName();

		List<Task> tasks = StaticValue.systemDao.search(Task.class, Cnd.where("groupName", "=", group.getName()));

		List<FileInfo> fileInfos = listFileInfosByGroup(groupName);

		//增加或查找不同
		InterProcessMutex lock = lockGroup(groupName);
		try {
			lock.acquire();
			JarService jarService = JarService.getOrCreate(groupName);
			if (jarService != null) {
				jarService.release();
			}
			//判断group是否存在。如果不存在。则进行安全添加
			if (zkDao.getZk().checkExists().forPath(GROUP_PATH + "/" + groupName) == null) {
				addGroup2Cluster(groupName, tasks, fileInfos);
				diffs = Collections.emptyList();
			} else {
				diffs = diffGroup(groupName, tasks, fileInfos);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			unLockAndDelete(lock);
		}


		/**
		 * 根据解决构建信息
		 */
		HostGroup hostGroup = new HostGroup();
		hostGroup.setSsl(StaticValue.IS_SSL);
		hostGroup.setCurrent(diffs.size() == 0);
		hostGroup.setWeight(diffs.size() > 0 ? 0 : 100);
		try {
			setData2ZKByEphemeral(HOST_GROUP_PATH + "/" + StaticValue.getHostPort() + "_" + groupName, JSONObject.toJSONBytes(hostGroup), new HostGroupWatcher(hostGroup));
		} catch (Exception e1) {
			e1.printStackTrace();
			LOG.error("add host group info err !!!!!", e1);
		}

		tasks.forEach(task -> {
			try {
				new JavaRunner(task).compile();

				Collection<CodeInfo.ExecuteMethod> executeMethods = task.codeInfo().getExecuteMethods();

				executeMethods.forEach(e -> {
					addMapping(task.getGroupName(), task.getName(), e.getMethod().getName());
				});

			} catch (Exception e) {
				LOG.error("compile {}/{} err ", task.getGroupName(), task.getName(), e);
			}
		});
		return diffs;
	}

	/**
	 * 取得所有的在线主机
	 *
	 * @return
	 * @throws Exception
	 */
	public List<String> getAllHosts() throws Exception {
		return getZk().getChildren().forPath(HOST_PATH);
	}

	/**
	 * 查询本地group和集群currentGroup差异
	 *
	 * @param groupName 组名称
	 * @param list      组内的所有任务
	 * @return
	 * @throws IOException
	 */
	private List<Different> diffGroup(String groupName, List<Task> list, List<FileInfo> fileInfos) throws Exception {

		final List<Different> diffs = new ArrayList<>();

		String path = GROUP_PATH + "/" + groupName;

		List<String> paths = zkDao.getZk().getChildren().forPath(path);
		Set<String> clusterTaskNames = paths.stream().filter(p -> !p.equals("file")).collect(Collectors.toSet());

		for (Task task : list) {
			clusterTaskNames.remove(task.getName());
			Different different = new Different();
			different.setPath(task.getName());
			different.setGroupName(groupName);
			different.setType(0);
			diffTask(task, different, null, null);
			if (different.getMessage() != null) {
				diffs.add(different);
			}

		}

		for (String clusterTaskName : clusterTaskNames) {
			Different different = new Different();
			different.setPath(clusterTaskName);
			different.setGroupName(groupName);
			different.setType(0);
			different.addMessage("本机中不存在此Task");
			diffs.add(different);

		}

		//先判断根结点
		FileInfo root = JSONObject.parseObject(getData2ZK(GROUP_PATH + "/" + groupName + "/file"), FileInfo.class);
		if (root != null && root.getMd5().equals(fileInfos.get(fileInfos.size() - 1).getMd5())) {
			LOG.info(groupName + " file md5 same so skip");
			return diffs;
		}

		LOG.info(groupName + " file changed find differents");


		Set<String> sets = new HashSet<>();
		walkAllDataNode(sets, GROUP_PATH + "/" + groupName + "/file");


		for (int i = 0; i < fileInfos.size() - 1; i++) {
			FileInfo lInfo = fileInfos.get(i);
			Different different = new Different();
			different.setGroupName(groupName);
			different.setPath(lInfo.getRelativePath());
			different.setType(1);

			if (!sets.contains(GROUP_PATH + "/" + groupName + "/file" + lInfo.getRelativePath())) {
				different.addMessage("文件在主版本中不存在");
			} else {
				sets.remove(GROUP_PATH + "/" + groupName + "/file" + lInfo.getRelativePath());
				byte[] data2ZK = getData2ZK(GROUP_PATH + "/" + groupName + "/file" + lInfo.getRelativePath());
				FileInfo cInfo = JSONObject.parseObject(data2ZK, FileInfo.class);
				if (!cInfo.equals(lInfo)) {
					different.addMessage("文件内容不一致");
				}

			}
			if (different.getMessage() != null) {
				diffs.add(different);
			}
		}

		for (String set : sets) {
			Different different = new Different();
			different.setGroupName(groupName);
			different.setPath(set.replaceFirst(GROUP_PATH + "/" + groupName + "/file", ""));
			different.setType(1);
			different.addMessage("文件在本地不存在");
			diffs.add(different);
		}

		for (Different diff : diffs) {
			LOG.info(diff.toString());
		}

		return diffs;

	}


	/**
	 * 刷新一个，固定的task 或者是 file。不和集群中的其他文件进行对比
	 *
	 * @param groupName
	 * @param taskNames
	 * @param fileInfos
	 * @throws Exception
	 */
	public void flushHostGroup(String groupName, Set<String> taskNames, List<FileInfo> fileInfos) throws Exception {

		List<Different> diffs = new ArrayList<>();

		if (taskNames != null && !taskNames.isEmpty()) {
			TaskService taskService = StaticValue.getSystemIoc().get(TaskService.class);
			for (String taskName : taskNames) {
				Different different = new Different();
				different.setPath(taskName);
				different.setGroupName(groupName);
				different.setType(0);
				diffTask(taskService.findTask(groupName, taskName), different, groupName, taskName);
				if (different.getMessage() != null) {
					diffs.add(different);
				}
			}
		}


		if (fileInfos != null && fileInfos.size() > 1) {
			for (FileInfo lInfo : fileInfos) {
				Different different = new Different();
				different.setGroupName(groupName);
				different.setPath(lInfo.getRelativePath());
				different.setType(1);

				FileInfo cInfo = getData((GROUP_PATH + "/" + groupName + "/file" + lInfo.getRelativePath()), FileInfo.class);
				if (cInfo == null) {
					different.addMessage("文件在主版本中不存在");
				} else {
					if (!cInfo.equals(lInfo)) {
						different.addMessage("文件内容不一致");
					}

				}
				if (different.getMessage() != null) {
					diffs.add(different);
				}
			}
		}


		HostGroup cHostGroup = hostGroupCache.get(StaticValue.getHostPort() + "_" + groupName);

		if (cHostGroup.isCurrent() != (diffs.size() == 0 ? true : false)) {
			HostGroup hostGroup = new HostGroup();
			hostGroup.setSsl(StaticValue.IS_SSL);
			hostGroup.setCurrent(diffs.size() == 0);
			hostGroup.setWeight(diffs.size() > 0 ? 0 : 100);
			try {
				setData2ZKByEphemeral(HOST_GROUP_PATH + "/" + StaticValue.getHostPort() + "_" + groupName, JSONObject.toJSONBytes(hostGroup), new HostGroupWatcher(hostGroup));
			} catch (Exception e1) {
				e1.printStackTrace();
				LOG.error("add host group info err !!!!!", e1);
			}
		}


	}

	/**
	 * 比较两个task是否一致
	 *
	 * @param task
	 */
	private void diffTask(Task task, Different different, String groupName, String taskName) {
		if (task != null) {
			groupName = task.getGroupName();
			taskName = task.getName();
		}

		try {
			byte[] bytes = getData2ZK(GROUP_PATH + "/" + groupName + "/" + taskName);

			if (bytes == null) {
				if (task == null) {
					return;
				}

				different.addMessage("集群中不存在此Task");
				return;
			} else {
				if (task == null) {
					different.addMessage("主机" + StaticValue.getHostPort() + "中不存在此Task");
					return;
				}
			}

			Task cluster = JSONObject.parseObject(bytes, Task.class);

			if (!Objects.equals(task.getCode(), cluster.getCode())) {
				different.addMessage("代码不一致");
			}
			if (!Objects.equals(task.getStatus(), cluster.getStatus())) {
				different.addMessage("状态不一致");
			}
			if (!Objects.equals(task.getType(), cluster.getType())) {
				different.addMessage("类型不一致");
			}
			if (task.getType() == 2 && !Objects.equals(task.getScheduleStr(), cluster.getScheduleStr())) {
				different.addMessage("定时计划不一致");
			}
			if (!Objects.equals(task.getDescription(), cluster.getDescription())) {
				different.addMessage("简介不一致");
			}
		} catch (Exception e) {
			e.printStackTrace();
			different.addMessage(e.getMessage());
		}

	}

	/**
	 * 如果这个group在集群中没有则，添加到集群
	 *
	 * @param groupName
	 * @param list
	 * @throws IOException
	 */
	private void addGroup2Cluster(String groupName, List<Task> list, List<FileInfo> fileInfos) throws IOException {

		list.stream().forEach(t -> {
			try {
				addTask(t);
			} catch (Exception e) {
				e.printStackTrace(); //这个异常很麻烦
				LOG.error("add task to cluster err ：" + t.getGroupName() + "/" + t.getName(), e);
			}
		});

		fileInfos.stream().forEach(fi -> {
			try {
				String relativePath = fi.getRelativePath();
				if ("/".equals(relativePath)) {
					setData2ZK(GROUP_PATH + "/" + groupName + "/file", JSONObject.toJSONBytes(fi));
				} else {
					setData2ZK(GROUP_PATH + "/" + groupName + "/file" + fi.getRelativePath(), JSONObject.toJSONBytes(fi));
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
		});


	}


	private List<FileInfo> listFileInfosByGroup(String groupName) throws IOException {

		final List<FileInfo> result = new ArrayList<>();

		if (!new File(StaticValue.GROUP_FILE, groupName).exists()) {
			LOG.warn(groupName + " not folder not exists so create it");
			new File(StaticValue.GROUP_FILE, groupName).mkdirs();
		}

		Path path = new File(StaticValue.GROUP_FILE, groupName).toPath();
		Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
			// 在访问子目录前触发该方法
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				File file = dir.toFile();
				if (!file.canRead() || file.isHidden() || file.getName().charAt(0) == '.') {
					LOG.warn(path.toString() + " is hidden or can not read or start whth '.' so skip it ");
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
				File file = path.toFile();
				if (!file.canRead() || file.isHidden() || file.getName().charAt(0) == '.') {
					LOG.warn(path.toString() + " is hidden or can not read or start whth '.' so skip it ");
					return FileVisitResult.CONTINUE;
				}
				try {
					result.add(new FileInfo(file));
				} catch (Exception e) {
					e.printStackTrace();
				}
				return FileVisitResult.CONTINUE;
			}
		});


		//先查缓存中是否存在用缓存做对比
		List<Long> collect = result.stream().map(fi -> fi.lastModified().getTime()).sorted().collect(Collectors.toList());
		String nowTimeMd5 = MD5Util.md5(collect.toString()); //当前文件的修改时间md5

		GroupCache groupCache = null;

		try {
			File cacheFile = new File(StaticValue.GROUP_FILE, groupName + ".cache");
			if (cacheFile.exists()) {
				String content = IOUtil.getContent(cacheFile, "utf-8");
				if (org.nlpcn.jcoder.util.StringUtil.isNotBlank(content)) {
					groupCache = JSONObject.parseObject(content, GroupCache.class);
				}
			}
		} catch (Exception e) {
			LOG.warn(groupName + " cache read err so create new ");
		}

		//本group本身的插入zk中用来比较md5加快对比
		FileInfo root = new FileInfo(path.toFile());
		root.setLength(result.stream().mapToLong(f -> f.getLength()).sum());

		if (groupCache != null && nowTimeMd5.equals(groupCache.getTimeMD5())) {
			LOG.info(groupName + " time md5 same so add it");
			root.setMd5(groupCache.getGroupMD5());
		} else {
			LOG.info("to computer md5 in gourp: " + groupName);
			List<String> ts = result.stream().map(fi -> fi.getRelativePath() + fi.getMd5()).sorted().collect(Collectors.toList());

			groupCache = new GroupCache();
			groupCache.setGroupMD5(MD5Util.md5(ts.toString()));
			groupCache.setTimeMD5(nowTimeMd5);
			groupCache.setPomMD5(JarService.getOrCreate(groupName).getPomMd5());
			root.setMd5(groupCache.getGroupMD5());

			IOUtil.Writer(new File(StaticValue.GROUP_FILE, groupName + ".cache").getCanonicalPath(), IOUtil.UTF8, JSONObject.toJSONString(groupCache));
		}


		result.add(root);

		return result;
	}


	/**
	 * 将文件同步更新到集群中
	 *
	 * @param groupName
	 */
	public void upCluster(String groupName, String relativePath) throws Exception {
		File file = new File(StaticValue.GROUP_FILE, groupName + relativePath);
		if (file.exists()) {
			setData2ZK(GROUP_PATH + "/" + groupName + "/file" + relativePath, JSONObject.toJSONBytes(new FileInfo(file)));
			LOG.info("up file to {} -> {}", groupName, relativePath);
		} else {
			zkDao.getZk().delete().deletingChildrenIfNeeded().forPath(GROUP_PATH + "/" + groupName + "/file" + relativePath);
			LOG.info("delete file to {} -> {}", groupName, relativePath);
		}
	}

	public <T> T getData(String path, Class<T> c) throws Exception {
		byte[] bytes = getData2ZK(path);
		if (bytes == null) {
			return null;
		}
		return JSONObject.parseObject(bytes, c);
	}

	/**
	 * 随机的获取一台和主版本同步着的主机
	 *
	 * @param groupName
	 * @return
	 */
	public List<String> getCurrentHostPort(String groupName) {
		List<String> collect = hostGroupCache.entrySet().stream().filter(e -> e.getValue().isCurrent()).map(e -> e.getKey()).filter(k -> groupName.equals(k.split("_")[1])).map(k -> k.split("_")[0]).collect(Collectors.toList());
		return collect;
	}

	/**
	 * 从主机集群中获取随机一个同步版本的机器，如果机器不存在则返回null
	 *
	 * @param groupName 组名称
	 * @return
	 */
	public String getRandomCurrentHostPort(String groupName) {
		List<String> collect = getCurrentHostPort(groupName);
		if (collect.size() == 0) {
			return null;
		}
		return collect.get(new Random().nextInt(collect.size()));
	}

	public CuratorFramework getZk() {
		return zkDao.getZk();
	}

	public TreeCache getMappingCache() {
		return mappingCache;
	}

	public ZKMap<Token> getTokenCache() {
		return tokenCache;
	}

	public ZKMap<HostGroup> getHostGroupCache() {
		return hostGroupCache;
	}

	public PathChildrenCache getGroupCache() {
		return groupCache;
	}

	/**
	 * 重置master
	 */
	public void resetMaster() {
		Optional.of(leader).ifPresent((o) -> closeWithoutException(o));

		try {
			leader.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
