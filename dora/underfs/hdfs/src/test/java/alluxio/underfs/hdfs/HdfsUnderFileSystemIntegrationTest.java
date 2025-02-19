/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.underfs.hdfs;

import static junit.framework.TestCase.assertEquals;

import alluxio.AlluxioURI;
import alluxio.underfs.UnderFileSystemConfiguration;

import org.apache.commons.io.IOUtils;
import org.apache.commons.text.CharacterPredicates;
import org.apache.commons.text.RandomStringGenerator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class HdfsUnderFileSystemIntegrationTest {
  @Rule
  public TemporaryFolder mTemp = new TemporaryFolder();
  private final Configuration mHdfsConfiguration = new Configuration();

  private MiniDFSCluster mCluster;
  private HdfsUnderFileSystem mUfs;

  private static final int BLOCK_SIZE = 1024 * 1024;

  @Before
  public void before() throws IOException {
    mHdfsConfiguration.set("dfs.name.dir", mTemp.newFolder("nn").getAbsolutePath());
    mHdfsConfiguration.set("dfs.data.dir", mTemp.newFolder("dn").getAbsolutePath());
    // 1MB block size for testing to save memory
    mHdfsConfiguration.setInt("dfs.block.size", BLOCK_SIZE);

    mCluster = new MiniDFSCluster.Builder(mHdfsConfiguration)
        .enableManagedDfsDirsRedundancy(false)
        .manageDataDfsDirs(false)
        .manageNameDfsDirs(false)
        .numDataNodes(1).build();

    UnderFileSystemConfiguration ufsConf =
        UnderFileSystemConfiguration.defaults(alluxio.conf.Configuration.global());

    mUfs =
        new HdfsUnderFileSystem(new AlluxioURI("/"), ufsConf, mHdfsConfiguration) {
          @Override
          protected FileSystem getFs() throws IOException {
            // Hookup HDFS mini cluster to HDFS UFS
            return mCluster.getFileSystem();
          }
        };
  }

  @After
  public void after() {
    if (mCluster != null) {
      mCluster.close();
    }
    if (mUfs != null) {
      mUfs.close();
    }
  }

  @Test
  public void testWriteMultiBlockFile() throws IOException {
    String testFilePath = "/test_file";
    // 16MB + 1 byte, 17 blocks
    int fileLength = 1024 * 1024 * 16 + 1;
    int numHdfsBlocks = (fileLength - 1) / BLOCK_SIZE + 1;

    RandomStringGenerator randomStringGenerator =
        new RandomStringGenerator.Builder()
            .withinRange('0', 'z')
            .filteredBy(CharacterPredicates.LETTERS, CharacterPredicates.DIGITS)
            .build();
    String fileContentToWrite = randomStringGenerator.generate(fileLength);

    OutputStream os = mUfs.create(testFilePath);
    os.write(fileContentToWrite.getBytes());
    os.close();

    InputStream is = mUfs.open(testFilePath);
    String readFileContent = IOUtils.toString(is);
    Assert.assertEquals(fileContentToWrite, readFileContent);

    assertEquals(fileLength, mUfs.getStatus(testFilePath).asUfsFileStatus().getContentLength());
    FileStatus status = mUfs.getFs().getFileStatus(new Path(testFilePath));
    assertEquals(numHdfsBlocks,
        mUfs.getFs().getFileBlockLocations(status, 0, status.getLen()).length);
  }

  @Test
  public void testWriteEmptyFile() throws IOException {
    String testFilePath = "/empty_file";
    OutputStream os = mUfs.create(testFilePath);
    os.close();
    assertEquals(0, mUfs.getStatus(testFilePath).asUfsFileStatus().getContentLength());
  }
}
