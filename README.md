![](./docs/images/logo.jpeg)

# Apache Kafka 2.8 源码分析

kafka通过gradle编译，并且使用了scala语言开发，所以要提前安装好这两个环境，2.8 版本推荐的 gradle 版本为：`6.8.1`，scala版本为：`2.13.5`。下载安装好以后，进入kafka源码根目录，执行：

- Linux / Mac

  ```shell
  ## 加上 -x test 是忽略掉测试类的编译, 可以加快编译速度
  ./gradlew build -x test

- Windows

  ```bash
  ## 加上 -x test 是忽略掉测试类的编译, 可以加快编译速度
  gradle.bat build -x test

第一次构建需要下载很多jar包，因此比较耗时，等待它构建成功后，导入到IDEA中：

1. 将 config 目录下的 log4j.properties 拷贝到 core 目录 src/main/resources下
2. 在`build.gradle`目录中引入日志依赖`compile group: 'org.slf4j', name: 'slf4j-log4j12', version: '1.7.30'`（对gradle不熟悉，暂时这样子解决）

3. 找到 core 目录下 scala / kafka.scala 文件，那个就是 kafka 的启动类，点击配置它，在 IDEA 的启动控制台中找到**Program arguments**，加上kafka的配置文件：`config/server.properties`
4. 直接启动（需要先启动zk，2.8还有一个特色就是去除了对zk的依赖，不过暂时还可以先用zk来快速启动）
5. 看到 kafka starded字样就表示启动成功了

------

**gradle加速**：在 ${USER_HOME}/.gradle/ 目录下创建 init.gradle 文件，添加以下内容：

```ini
allprojects {
    repositories {
        def ALIYUN_REPOSITORY_URL = 'https://maven.aliyun.com/repository/public'
        all { ArtifactRepository repo ->
            if(repo instanceof MavenArtifactRepository){
                def url = repo.url.toString()
                if (url.startsWith('https://repo1.maven.org/maven2')) {
                    project.logger.lifecycle "Repository ${repo.url} replaced by $ALIYUN_REPOSITORY_URL."
                    remove repo
                }
            }
        }
        maven { url ALIYUN_REPOSITORY_URL }
    }
}
```

------

**可能异常**：源码编译过程中可能出现

```shell
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':react-native-linear-gradient:compileDebugJavaWithJavac'.
> Could not find tools.jar. Please check that /Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home contains a valid JDK installation.
```

解决方式有两步，步骤如下：

```shell
/usr/libexec/java_home -V | grep jdk

## 通过上面命令，查询到JVM的路径如下
Matching Java Virtual Machines (1):
1.8.0_272 (x86_64) "AdoptOpenJDK" - "AdoptOpenJDK 8" /Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home
/Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home
```

修改`.zshrc`文件，执行命令`vim .zshrc`，将下面的内容拷贝进去

```shell
## 注意 JAVA_HOME 路径要通过前面命令查询的JVM路径
export JAVA_HOME=/Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

参考连接：[stackoverflow](https://stackoverflow.com/questions/64968851/could-not-find-tools-jar-please-check-that-library-internet-plug-ins-javaapple)

