package org.nlpcn.jcoder.domain;

import org.nlpcn.jcoder.run.CodeRuntimeException;
import org.nlpcn.jcoder.service.JarService;
import org.nutz.ioc.Ioc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * the code init info
 *
 * @author ansj
 */
public class CodeInfo {

	private static final Logger LOG = LoggerFactory.getLogger(CodeInfo.class);

	private Class<?> classz;

	private Object JavaObject;

	private Map<String, ExecuteMethod> executeMethods = new HashMap<>();

	private ExecuteMethod defaultMethod;

	private Ioc ioc;

	private ClassLoader classLoader;

	private boolean single = true;

	public boolean iocChanged(String groupName) {
		return this.ioc != JarService.getOrCreate(groupName).getIoc();
	}

	public Ioc getIoc() {
		return ioc;
	}

	public boolean classLoaderChanged(String groupName) {
		return this.classLoader != JarService.getOrCreate(groupName).getEngine().getClassLoader();
	}

	public void setioc(Ioc ioc) {
		this.ioc = ioc;
	}

	public ClassLoader getClassLoader() {
		return this.classLoader;
	}

	public void setClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	public Class<?> getClassz() {
		return classz;
	}

	public void setClassz(Class<?> classz) {
		this.classz = classz;
	}

	public Object getJavaObject() {
		return JavaObject;
	}

	public void setJavaObject(Object javaObject) {
		JavaObject = javaObject;
	}

	public Collection<ExecuteMethod> getExecuteMethods() {
		return executeMethods.values();
	}

	public boolean isSingle() {
		return single;
	}

	public void setSingle(boolean single) {
		this.single = single;
	}

	public void addMethod(ExecuteMethod method) {
		if (defaultMethod == null) {
			defaultMethod = method;
		}
		this.executeMethods.put(method.getName(), method);
	}

	public void addMethod(Method method, Set<String> methodTypeSet, boolean rpc, boolean restful) {
		addMethod(new ExecuteMethod(method, methodTypeSet, rpc, restful));
	}

	public ExecuteMethod getDefaultMethod() {
		if (defaultMethod == null) {
			if (executeMethods.size() == 0) {
				throw new CodeRuntimeException(classz.getName() + " not have any @Execute annotation you must set one ");
			}
			defaultMethod = executeMethods.entrySet().iterator().next().getValue();
		}
		return defaultMethod;
	}

	/**
	 * 通过方法名称获得方法
	 *
	 * @param methodName
	 * @return
	 */
	public ExecuteMethod getExecuteMethod(String methodName) {
		return executeMethods.get(methodName);
	}

	/**
	 * 执行方法的抽象类
	 *
	 * @author Ansj
	 */
	public class ExecuteMethod {
		private Method method;
		private Set<String> methodTypeSet = new HashSet<>();
		private boolean rpc;
		private boolean restful;

		public ExecuteMethod(Method method, Set<String> methodTypeSet, boolean rpc, boolean restful) {
			super();
			this.method = method;
			this.methodTypeSet = methodTypeSet;
			this.rpc = rpc;
			this.restful = restful;
		}

		public Method getMethod() {
			return method;
		}

		public void setMethod(Method method) {
			this.method = method;
		}

		public Set<String> getMethodTypeSet() {
			return methodTypeSet;
		}

		public void setMethodTypeSet(Set<String> methodTypeSet) {
			this.methodTypeSet = methodTypeSet;
		}

		public boolean isRpc() {
			return rpc;
		}

		public void setRpc(boolean rpc) {
			this.rpc = rpc;
		}

		public boolean isRestful() {
			return restful;
		}

		public void setRestful(boolean restful) {
			this.restful = restful;
		}

		public String getName() {
			return this.method.getName();
		}

	}

}
