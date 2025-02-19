---
layout: global
title: Tencent COS
---

This guide describes the instructions to configure [Tencent COS](https://cloud.tencent.com/product/cos) as Alluxio's
under storage system. 

Alluxio support two different implementations of under storage system for Tencent COS:

* [COS](https://cloud.tencent.com/product/cos)
: Tencent Cloud Object Storage (COS) is a distributed storage service offered by Tencent Cloud for unstructured data and accessible via HTTP/HTTPS protocols. It can store massive amounts of data and features imperceptible bandwidth and capacity expansion, making it a perfect data pool for big data computation and analytics.

* [COSN](https://hadoop.apache.org/docs/stable/hadoop-cos/cloud-storage/index.html), also known as Hadoop-COS
: COSN, also known as Hadoop-COS, is a client that makes the upper computing systems based on HDFS be able to use [Tencent Cloud Object Storage (COS)](https://cloud.tencent.com/product/cos) as its underlying storage system. 

## Prerequisites

In preparation for using COS with Alluxio, create a new bucket or use an existing bucket.
You should also note the directory you want to use in that bucket, either by creating a new directory in the bucket or using an existing one.

{% navtabs Prerequisites %}
{% navtab COS %}

For the purposes of this guide, the COS bucket name is called
`COS_BUCKET`, and the directory in that bucket is called `COS_DIRECTORY`.

You also need to provide APPID and REGION. In this guide, the APPID is called `COS_APP_ID`, and the REGION is called `COS_REGION`. For more information, please refer [here](https://cloud.tencent.com/document/product/436/7751).

{% endnavtab %}
{% navtab COSN %}

For the purposes of this guide, the COSN Bucket name is called `COSN_BUCKET`, the directory in that bucket is called `COSN_DIRECTORY`, and COSN Bucket region is called `COSN_REGION` which specifies the region of your bucket.

{% endnavtab %}
{% endnavtabs %}

## Basic Setup

Create `conf/alluxio-site.properties` and `conf/core-site.xml` if they do not exist.

```shell
$ cp conf/alluxio-site.properties.template conf/alluxio-site.properties
$ cp conf/core-site.xml.template conf/core-site.xml
```

{% navtabs Setup %}
{% navtab COS %}

Configure Alluxio to use COS as its under storage system by modifying `conf/alluxio-site.properties`.
Specify an **existing** COS bucket and directory as the under storage system by modifying
`conf/alluxio-site.properties` to include:

```properties
alluxio.dora.client.ufs.root=cos://COS_BUCKET/COS_DIRECTORY/
```

Note that if you want to mount the whole cos bucket, add a trailing slash after the bucket name
(e.g. `cos://COS_BUCKET/`).

Specify the COS credentials for COS access by setting `fs.cos.access.key` and `fs.cos.secret.key` in
`alluxio-site.properties`.

```properties
fs.cos.access.key=<COS_SECRET_ID>
fs.cos.secret.key=<COS_SECRET_KEY>
```

Specify the COS region by setting `fs.cos.region` in `alluxio-site.properties` (e.g. ap-beijing) and `fs.cos.app.id`.

```properties
fs.cos.region=<COS_REGION>
fs.cos.app.id=<COS_APP_ID>
```

{% endnavtab %}
{% navtab COSN %}

Configure Alluxio to use COSN as its under storage system by modifying `conf/alluxio-site.properties` and `conf/core-site.xml`.
Specify an existing COS bucket and directory as the under storage system by modifying
`conf/alluxio-site.properties` to include:

```properties
alluxio.dora.client.ufs.root=cosn://COSN_BUCKET/COSN_DIRECTORY/
```

Specify COS configuration information in order to access COS by modifying `conf/core-site.xml` to include:

```xml
<property>
   <name>fs.cosn.impl</name>
   <value>org.apache.hadoop.fs.CosFileSystem</value>
</property>
<property>
  <name>fs.AbstractFileSystem.cosn.impl</name>
  <value>org.apache.hadoop.fs.CosN</value>
</property>
<property>
  <name>fs.cosn.userinfo.secretKey</name>
  <value>xxxx</value>
</property>
<property>
  <name>fs.cosn.userinfo.secretId</name>
  <value>xxxx</value>
</property>
<property>
  <name>fs.cosn.bucket.region</name>
  <value>xx</value>
</property>
```

The above is the most basic configuration. For more configuration please refer to [here](https://hadoop.apache.org/docs/r3.3.1/hadoop-cos/cloud-storage/index.html).

{% endnavtab %}
{% endnavtabs %}

After these changes, Alluxio should be configured to work with COS or COSN as its under storage system.

## Running Alluxio Locally with COS

Start the Alluxio servers:

```shell
$ ./bin/alluxio format
$ ./bin/alluxio-start.sh local
```

This will start an Alluxio master and an Alluxio worker. You can see the master UI at
[http://localhost:19999](http://localhost:19999).

Before running an example program, please make sure the root mount point
set in the `conf/alluxio-site.properties` is a valid path in the ufs.
Make sure the user running the example program has write permissions to the alluxio file system.

Run a simple example program:

```shell
$ ./bin/alluxio runTests
```

{% navtabs Test %}
{% navtab COS %}

Visit your COS directory at `COS_BUCKET/COS_DIRECTORY` to verify the files and directories created by Alluxio exist.
For this test, you should see files named like:

```
COS_BUCKET/COS_DIRECTORY/default_tests_files/BASIC_CACHE_THROUGH
```

{% endnavtab %}
{% navtab COSN %}

Visit your COSN directory at `COSN_BUCKET/COSN_DIRECTORY` to verify the files and directories created by Alluxio exist.
For this test, you should see files named like:

```
COSN_BUCKET/COSN_DIRECTORY/default_tests_files/BASIC_CACHE_THROUGH
```

{% endnavtab %}
{% endnavtabs %}

To stop Alluxio, you can run:
```shell
$ ./bin/alluxio-stop.sh local
```

## Advanced Setup

### [Experimental] COS multipart upload

The default upload method uploads one file completely from start to end in one go. We use multipart-upload method to upload one file by multiple parts, every part will be uploaded in one thread. It won't generate any temporary files while uploading.

To enable COS multipart upload, you need to modify `conf/alluxio-site.properties` to include:

```properties
alluxio.underfs.cos.multipart.upload.enabled=true
```

There are other parameters you can specify in `conf/alluxio-site.properties` to make the process faster and better.

```properties
# Timeout for uploading part when using multipart upload.
alluxio.underfs.object.store.multipart.upload.timeout
```
```properties
# Thread pool size for COS multipart upload.
alluxio.underfs.cos.multipart.upload.threads
```
```properties
# Multipart upload partition size for COS. The default partition size is 64MB. 
alluxio.underfs.cos.multipart.upload.partition.size
```



