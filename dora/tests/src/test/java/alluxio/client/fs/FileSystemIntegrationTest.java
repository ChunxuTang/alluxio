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

package alluxio.client.fs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.annotation.dora.DoraTestTodoItem;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.FileSystemTestUtils;
import alluxio.client.file.ListStatusPartialResult;
import alluxio.client.file.URIStatus;
import alluxio.collections.Pair;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AlluxioException;
import alluxio.exception.DirectoryNotEmptyException;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidPathException;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.DeletePOptions;
import alluxio.grpc.FileSystemMasterCommonPOptions;
import alluxio.grpc.ListStatusPartialPOptions;
import alluxio.grpc.SetAttributePOptions;
import alluxio.grpc.TtlAction;
import alluxio.grpc.WritePType;
import alluxio.master.LocalAlluxioCluster;
import alluxio.testutils.BaseIntegrationTest;
import alluxio.testutils.LocalAlluxioClusterResource;
import alluxio.underfs.UnderFileSystem;
import alluxio.util.CommonUtils;
import alluxio.util.UnderFileSystemUtils;
import alluxio.util.WaitForOptions;
import alluxio.util.io.PathUtils;
import alluxio.wire.BlockLocationInfo;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Integration tests for Alluxio Client (reuse the {@link LocalAlluxioCluster}).
 */
@Ignore
@DoraTestTodoItem(action = DoraTestTodoItem.Action.FIX, owner = "jiacheng",
    comment = "many tests fail due to a few missing APIs")
public final class FileSystemIntegrationTest extends BaseIntegrationTest {
  private static final byte[] TEST_BYTES = "TestBytes".getBytes();
  private static final int USER_QUOTA_UNIT_BYTES = 1000;
  @Rule
  public LocalAlluxioClusterResource mLocalAlluxioClusterResource =
      new LocalAlluxioClusterResource.Builder()
          .setProperty(PropertyKey.USER_FILE_BUFFER_BYTES, USER_QUOTA_UNIT_BYTES)
          .build();
  private FileSystem mFileSystem = null;
  private CreateFilePOptions mWriteBoth;
  private UnderFileSystem mUfs;

  @Rule
  public ExpectedException mThrown = ExpectedException.none();

  @Before
  public void before() throws Exception {
    mFileSystem = mLocalAlluxioClusterResource.get().getClient();
    mWriteBoth = CreateFilePOptions.newBuilder().setRecursive(true)
        .setWriteType(WritePType.CACHE_THROUGH).build();
    mUfs = UnderFileSystem.Factory.createForRoot(Configuration.global());
  }

  @Test
  public void getRoot() throws Exception {
    Assert.assertEquals(0, mFileSystem.getStatus(new AlluxioURI("/")).getFileId());
  }

  @Test
  public void createFile() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    for (int k = 1; k < 5; k++) {
      AlluxioURI uri = new AlluxioURI(uniqPath + k);
      mFileSystem.createFile(uri, mWriteBoth).close();
      Assert.assertNotNull(mFileSystem.getStatus(uri));
    }
  }

  @Test
  public void deleteFile() throws Exception {
    String uniqPath = PathUtils.uniqPath();

    for (int k = 0; k < 5; k++) {
      AlluxioURI fileURI = new AlluxioURI(uniqPath + k);
      FileSystemTestUtils.createByteFile(mFileSystem, fileURI.getPath(), k, mWriteBoth);
      Assert.assertTrue(mFileSystem.getStatus(fileURI).getInAlluxioPercentage() == 100);
      Assert.assertNotNull(mFileSystem.getStatus(fileURI));
    }

    for (int k = 0; k < 5; k++) {
      AlluxioURI fileURI = new AlluxioURI(uniqPath + k);
      mFileSystem.delete(fileURI);
      Assert.assertFalse(mFileSystem.exists(fileURI));
      mThrown.expect(FileDoesNotExistException.class);
      mFileSystem.getStatus(fileURI);
    }
  }

  /**
   * Tests if a directory with in-progress writes can be deleted recursively.
   */
  @Test
  public void deleteDirectoryWithPersistedWritesInProgress() throws Exception {
    final AlluxioURI testFolder = new AlluxioURI("/testFolder");
    mFileSystem.createDirectory(testFolder,
        CreateDirectoryPOptions.newBuilder().setWriteType(WritePType.CACHE_THROUGH).build());
    FileOutStream out = mFileSystem.createFile(new AlluxioURI("/testFolder/testFile"),
        CreateFilePOptions.newBuilder().setWriteType(WritePType.CACHE_THROUGH).build());
    out.write(TEST_BYTES);
    out.flush();
    // Need to wait for the file to be flushed, see ALLUXIO-2899
    CommonUtils.waitFor("File flush.", () -> {
      try {
        return mUfs.listStatus(mFileSystem.getStatus(testFolder).getUfsPath()).length > 0;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, WaitForOptions.defaults().setTimeoutMs(5 * Constants.SECOND_MS));
    mFileSystem.delete(new AlluxioURI("/testFolder"),
        DeletePOptions.newBuilder().setRecursive(true).build());
    Assert.assertFalse(mFileSystem.exists(new AlluxioURI("/testFolder")));
    mThrown.expect(IOException.class);
    out.close();
  }

  @Test
  public void getFileStatus() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    int writeBytes = USER_QUOTA_UNIT_BYTES * 2;
    AlluxioURI uri = new AlluxioURI(uniqPath);
    FileSystemTestUtils.createByteFile(mFileSystem, uri.getPath(), writeBytes, mWriteBoth);
    Assert.assertTrue(mFileSystem.getStatus(uri).getInAlluxioPercentage() == 100);

    Assert.assertTrue(mFileSystem.getStatus(uri).getPath().equals(uniqPath));
  }

  @Test
  public void renameFileTest1() throws Exception {
    String uniqPath = PathUtils.uniqPath();
    AlluxioURI path1 = new AlluxioURI(uniqPath + 1);
    mFileSystem.createFile(path1, mWriteBoth).close();
    for (int k = 1; k < 10; k++) {
      AlluxioURI fileA = new AlluxioURI(uniqPath + k);
      AlluxioURI fileB = new AlluxioURI(uniqPath + (k + 1));
      URIStatus existingFile = mFileSystem.getStatus(fileA);
      long oldFileId = existingFile.getFileId();
      Assert.assertNotNull(existingFile);
      mFileSystem.rename(fileA, fileB);
      URIStatus renamedFile = mFileSystem.getStatus(fileB);
      Assert.assertNotNull(renamedFile);
      Assert.assertEquals(oldFileId, renamedFile.getFileId());
    }
  }

  @Test
  public void renameFileTest2() throws Exception {
    AlluxioURI uniqUri = new AlluxioURI(PathUtils.uniqPath());
    mFileSystem.createFile(uniqUri, mWriteBoth).close();
    URIStatus f = mFileSystem.getStatus(uniqUri);
    long oldFileId = f.getFileId();
    mFileSystem.rename(uniqUri, uniqUri);
    Assert.assertEquals(oldFileId, mFileSystem.getStatus(uniqUri).getFileId());
  }

  /**
   * Creates another directory on the local filesystem, alongside the existing Ufs, to be used as a
   * second Ufs.
   *
   * @return the path of the alternate Ufs directory
   */
  private String createAlternateUfs() throws Exception {
    AlluxioURI parentURI =
        new AlluxioURI(Configuration.getString(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS))
            .getParent();
    String alternateUfsRoot = parentURI.join("alternateUnderFSStorage").toString();
    UnderFileSystemUtils.mkdirIfNotExists(mUfs, alternateUfsRoot);
    return alternateUfsRoot;
  }

  /**
   * Deletes the alternate under file system directory.
   *
   * @param alternateUfsRoot the root of the alternate Ufs
   */
  private void destroyAlternateUfs(String alternateUfsRoot) throws Exception {
    UnderFileSystemUtils.deleteDirIfExists(mUfs, alternateUfsRoot);
  }

  @Test
  public void mountAlternateUfs() throws Exception {
    String alternateUfsRoot = createAlternateUfs();
    try {
      String filePath = PathUtils.concatPath(alternateUfsRoot, "file1");
      UnderFileSystemUtils.touch(mUfs, filePath);
      mFileSystem.mount(new AlluxioURI("/d1"), new AlluxioURI(alternateUfsRoot));
      Assert.assertEquals("file1", mFileSystem.listStatus(new AlluxioURI("/d1")).get(0).getName());
    } finally {
      destroyAlternateUfs(alternateUfsRoot);
    }
  }

  @Test
  public void mountAlternateUfsSubdirs() throws Exception {
    String alternateUfsRoot = createAlternateUfs();
    try {
      String dirPath1 = PathUtils.concatPath(alternateUfsRoot, "dir1");
      String dirPath2 = PathUtils.concatPath(alternateUfsRoot, "dir2");
      UnderFileSystemUtils.mkdirIfNotExists(mUfs, dirPath1);
      UnderFileSystemUtils.mkdirIfNotExists(mUfs, dirPath2);
      String filePath1 = PathUtils.concatPath(dirPath1, "file1");
      String filePath2 = PathUtils.concatPath(dirPath2, "file2");
      UnderFileSystemUtils.touch(mUfs, filePath1);
      UnderFileSystemUtils.touch(mUfs, filePath2);

      mFileSystem.mount(new AlluxioURI("/d1"), new AlluxioURI(dirPath1));
      mFileSystem.mount(new AlluxioURI("/d2"), new AlluxioURI(dirPath2));
      Assert.assertEquals("file1", mFileSystem.listStatus(new AlluxioURI("/d1")).get(0).getName());
      Assert.assertEquals("file2", mFileSystem.listStatus(new AlluxioURI("/d2")).get(0).getName());
    } finally {
      destroyAlternateUfs(alternateUfsRoot);
    }
  }

  @Test
  public void mountPrefixUfs() throws Exception {
    // Primary UFS cannot be re-mounted
    String ufsRoot = Configuration.getString(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS);
    String ufsSubdir = PathUtils.concatPath(ufsRoot, "dir1");
    UnderFileSystemUtils.mkdirIfNotExists(mUfs, ufsSubdir);
    try {
      mFileSystem.mount(new AlluxioURI("/dir"), new AlluxioURI(ufsSubdir));
      Assert.fail("Cannot remount primary ufs.");
    } catch (AlluxioException e) {
      // Exception expected
    }

    String alternateUfsRoot = createAlternateUfs();
    try {
      String midDirPath = PathUtils.concatPath(alternateUfsRoot, "mid");
      String innerDirPath = PathUtils.concatPath(midDirPath, "inner");
      UnderFileSystemUtils.mkdirIfNotExists(mUfs, innerDirPath);
      mFileSystem.mount(new AlluxioURI("/mid"), new AlluxioURI(midDirPath));
      // Cannot mount suffix of already-mounted directory
      try {
        mFileSystem.mount(new AlluxioURI("/inner"), new AlluxioURI(innerDirPath));
        Assert.fail("Cannot mount suffix of already-mounted directory");
      } catch (AlluxioException e) {
        // Exception expected, continue
      }
      // Cannot mount prefix of already-mounted directory
      try {
        mFileSystem.mount(new AlluxioURI("/root"), new AlluxioURI(alternateUfsRoot));
        Assert.fail("Cannot mount prefix of already-mounted directory");
      } catch (AlluxioException e) {
        // Exception expected, continue
      }
    } finally {
      destroyAlternateUfs(alternateUfsRoot);
    }
  }

  @Test
  public void mountShadowUfs() throws Exception {
    String ufsRoot = Configuration.getString(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS);
    String ufsSubdir = PathUtils.concatPath(ufsRoot, "dir1");
    UnderFileSystemUtils.mkdirIfNotExists(mUfs, ufsSubdir);

    String alternateUfsRoot = createAlternateUfs();
    try {
      String subdirPath = PathUtils.concatPath(alternateUfsRoot, "subdir");
      UnderFileSystemUtils.mkdirIfNotExists(mUfs, subdirPath);
      // Cannot mount to path that shadows a file in the primary UFS
      mFileSystem.mount(new AlluxioURI("/dir1"), new AlluxioURI(subdirPath));
      Assert.fail("Cannot mount to path that shadows a file in the primary UFS");
    } catch (AlluxioException e) {
      // Exception expected, continue
    } finally {
      destroyAlternateUfs(alternateUfsRoot);
    }
  }

  @Test
  public void getBlockLocations() throws Exception {

    // Test not in alluxio
    AlluxioURI testFile = new AlluxioURI("/test1");
    FileSystemTestUtils.createByteFile(mFileSystem, testFile, CreateFilePOptions.newBuilder()
        .setWriteType(WritePType.THROUGH).setBlockSizeBytes(4).build(), 100);
    List<BlockLocationInfo> locations = mFileSystem.getBlockLocations(testFile);
    assertEquals("should have 25 blocks", 25, locations.size());
    long lastOffset = -1;
    for (BlockLocationInfo location : locations) {
      assertEquals("block " + location.getBlockInfo() + " should have single worker",
          1, location.getLocations().size());
      assertTrue("block " + location.getBlockInfo() + " should have offset larger than "
              + lastOffset, location.getBlockInfo().getOffset() > lastOffset);
      lastOffset = location.getBlockInfo().getOffset();
    }

    // Test in alluxio
    testFile = new AlluxioURI("/test2");
    FileSystemTestUtils.createByteFile(mFileSystem, testFile, CreateFilePOptions.newBuilder()
            .setWriteType(WritePType.CACHE_THROUGH).setBlockSizeBytes(100).build(), 500);
    locations = mFileSystem.getBlockLocations(testFile);
    assertEquals("Should have 5 blocks", 5, locations.size());
    lastOffset = -1;
    for (BlockLocationInfo location : locations) {
      assertEquals("block " + location.getBlockInfo() + " should have single worker",
          1, location.getLocations().size());
      assertTrue("block " + location.getBlockInfo() + " should have offset larger than "
          + lastOffset, location.getBlockInfo().getOffset() > lastOffset);
      lastOffset = location.getBlockInfo().getOffset();
    }
  }

  @Test
  public void testMultiSetAttribute() throws Exception {
    AlluxioURI testFile = new AlluxioURI("/test1");
    FileSystemTestUtils.createByteFile(mFileSystem, testFile, WritePType.MUST_CACHE, 512);
    long expectedTtl = Configuration.getMs(PropertyKey.USER_FILE_CREATE_TTL);
    URIStatus stat = mFileSystem.getStatus(testFile);
    assertEquals("TTL should be equal to configuration", expectedTtl, stat.getTtl());

    // Ttl should be updated to newTtl
    long newTtl = 14402478;
    mFileSystem.setAttribute(testFile,
        SetAttributePOptions.newBuilder().setCommonOptions(
            FileSystemMasterCommonPOptions.newBuilder().setTtl(newTtl).build()).build());
    stat = mFileSystem.getStatus(testFile);
    assertEquals("Ttl should be the updated", newTtl, stat.getTtl());

    // SetAttribute with same ttl should not modify the lastModifiedTime
    long lastModifiedTime = stat.getLastModificationTimeMs();
    mFileSystem.setAttribute(testFile,
        SetAttributePOptions.newBuilder().setCommonOptions(
            FileSystemMasterCommonPOptions.newBuilder().setTtl(newTtl).build()).build());
    stat = mFileSystem.getStatus(testFile);
    assertEquals("Ttl should not change", newTtl, stat.getTtl());
    assertEquals("LastModifiedTime should not change", lastModifiedTime,
        stat.getLastModificationTimeMs());

    // Owner should get updated and Ttl should not change
    String newOwner = "testOwner";
    mFileSystem.setAttribute(testFile,
        SetAttributePOptions.newBuilder().setOwner(newOwner).build());
    stat = mFileSystem.getStatus(testFile);
    assertEquals("TTL should not change", newTtl, stat.getTtl());
    assertEquals("Owner should be updated", newOwner, stat.getOwner());
  }

  @LocalAlluxioClusterResource.Config(
      confParams = {
          PropertyKey.Name.USER_FILE_CREATE_TTL_ACTION, "FREE"
      })
  @Test
  public void testTtlActionSetAttribute() throws Exception {
    AlluxioURI testFile = new AlluxioURI("/test1");
    FileSystemTestUtils.createByteFile(mFileSystem, testFile, WritePType.MUST_CACHE, 512);
    TtlAction expectedAction =
        Configuration.getEnum(PropertyKey.USER_FILE_CREATE_TTL_ACTION, TtlAction.class);
    URIStatus stat = mFileSystem.getStatus(testFile);
    assertEquals("TTL action should be same", expectedAction, stat.getTtlAction());

    TtlAction newTtlAction = TtlAction.DELETE;
    long newTtl = 123400000;
    mFileSystem.setAttribute(testFile, SetAttributePOptions.newBuilder().setCommonOptions(
            FileSystemMasterCommonPOptions.newBuilder().setTtl(newTtl).build()).build());
    stat = mFileSystem.getStatus(testFile);
    assertEquals("TTL should be same", newTtl, stat.getTtl());
    assertEquals("TTL action should be same", expectedAction, stat.getTtlAction());
    mFileSystem.setAttribute(testFile, SetAttributePOptions.newBuilder().setCommonOptions(
            FileSystemMasterCommonPOptions.newBuilder().setTtlAction(newTtlAction).build())
        .build());
    stat = mFileSystem.getStatus(testFile);
    assertEquals("TTL should be same", newTtl, stat.getTtl());
    assertEquals("TTL action should be same", newTtlAction, stat.getTtlAction());
  }

// Test exception cases for all FileSystem RPCs

  @Test
  public void createExistingDirectory() throws Exception {
    AlluxioURI path = new AlluxioURI("/dir");
    mFileSystem.createDirectory(path);
    mThrown.expect(FileAlreadyExistsException.class);
    mFileSystem.createDirectory(path);
  }

  @Test
  public void createDirectoryOnTopOfFile() throws Exception {
    AlluxioURI path = new AlluxioURI("/dir");
    FileSystemTestUtils.createByteFile(mFileSystem, path, CreateFilePOptions.getDefaultInstance(),
        10);
    mThrown.expect(FileAlreadyExistsException.class);
    mFileSystem.createDirectory(path);
  }

  @Test
  public void createDirectoryInvalidPath() throws Exception {
    mThrown.expect(InvalidPathException.class);
    mFileSystem.createDirectory(new AlluxioURI("not a path"));
  }

  @Test
  public void createExistingFile() throws Exception {
    AlluxioURI path = new AlluxioURI("/file");
    mFileSystem.createFile(path).close();
    mThrown.expect(FileAlreadyExistsException.class);
    mFileSystem.createFile(path);
  }

  @Test
  public void createFileInvalidPath() throws Exception {
    mThrown.expect(InvalidPathException.class);
    mFileSystem.createFile(new AlluxioURI("not a path"));
  }

  @Test
  public void deleteNonexistingPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.delete(new AlluxioURI("/dir"));
  }

  @Test
  public void deleteNonexistingNestedPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.delete(new AlluxioURI("/dir/dir"));
  }

  @Test
  public void deleteNonemptyDirectory() throws Exception {
    AlluxioURI dir = new AlluxioURI("/dir");
    mFileSystem.createDirectory(dir);
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "file"))).close();
    mThrown.expect(DirectoryNotEmptyException.class);
    mFileSystem.delete(dir, DeletePOptions.getDefaultInstance());
  }

  @Test
  public void existsNonexistingPath() throws Exception {
    AlluxioURI path = new AlluxioURI("/path");
    assertFalse(mFileSystem.exists(path));
  }

  @Test
  public void existsNonexistingNestedPath() throws Exception {
    AlluxioURI path = new AlluxioURI("/dir/path");
    assertFalse(mFileSystem.exists(path));
  }

  @Test
  public void freeNonexistingPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.free(new AlluxioURI("/path"));
  }

  @Test
  public void freeNonexistingNestedPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.free(new AlluxioURI("/dir/path"));
  }

  @Test
  public void getStatusNonexistingPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.getStatus(new AlluxioURI("/path"));
  }

  @Test
  public void getStatusNonexistingNestedPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.getStatus(new AlluxioURI("/dir/path"));
  }

  @Test
  public void listStatusNonexistingPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.listStatus(new AlluxioURI("/path"));
  }

  @Test
  public void openFileNonexistingPath() throws Exception {
    AlluxioURI path = new AlluxioURI("/path");
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.openFile(path);
  }

  @Test
  public void renameNonexistingPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.rename(new AlluxioURI("/path1"), new AlluxioURI("/path1"));
  }

  @Test
  public void setAttributeNonexistingPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.setAttribute(new AlluxioURI("/path"));
  }

  @Test
  public void getBlockLocationNonExistingPath() throws Exception {
    mThrown.expect(FileDoesNotExistException.class);
    mFileSystem.getBlockLocations(new AlluxioURI("/path"));
  }

  Pair<List<String>, ListStatusPartialResult> partialList(
      AlluxioURI path, ListStatusPartialPOptions options) throws Exception {
    ListStatusPartialResult result = mFileSystem.listStatusPartial(path, options);
    return new Pair<>(result.getListings().stream().map(URIStatus::getName)
        .collect(Collectors.toList()), result);
  }

  @Test
  public void listStatusPartial() throws Exception {
    AlluxioURI dir = new AlluxioURI("/dir");
    mFileSystem.createDirectory(dir);
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "a"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "b"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "e"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "f"))).close();
    Pair<List<String>, ListStatusPartialResult> result = partialList(dir,
        ListStatusPartialPOptions.newBuilder().setBatchSize(2).build());
    assertEquals(Arrays.asList("a", "b"), result.getFirst());
    assertTrue(result.getSecond().isTruncated());
    result = partialList(dir, ListStatusPartialPOptions.newBuilder()
        .setOffsetId(result.getSecond().getListings().get(
            result.getSecond().getListings().size() - 1).getFileId()).build());
    assertEquals(Arrays.asList("e", "f"), result.getFirst());
    assertFalse(result.getSecond().isTruncated());
  }

  @Test
  public void listStatusStartAfterLoop() throws Exception {
    AlluxioURI dir = new AlluxioURI("/dir");
    mFileSystem.createDirectory(dir);
    int fileCount = 100;
    for (int i = 0; i < fileCount; i++) {
      mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(
          dir, String.format("%05d", i)))).close();
    }
    int batchSize = 2;
    String startAfter = "";
    for (int i = 0; i < fileCount; i += batchSize) {
      Pair<List<String>, ListStatusPartialResult> result = partialList(dir,
          ListStatusPartialPOptions.newBuilder().setStartAfter(startAfter)
              .setBatchSize(batchSize).build());
      List<String> expectedResult = new ArrayList<>();
      for (int j = i; j < Math.min(fileCount, i + batchSize); j++) {
        expectedResult.add(String.format("%05d", j));
      }
      assertEquals(expectedResult, result.getFirst());
      if ((i + batchSize) < fileCount) {
        assertTrue(result.getSecond().isTruncated());
      } else {
        assertFalse(result.getSecond().isTruncated());
      }
      List<URIStatus> listings = result.getSecond().getListings();
      if (!listings.isEmpty()) {
        startAfter = listings.get(listings.size() - 1).getPath();
      }
    }
  }

  @Test
  public void listStatusPartialLoop() throws Exception {
    AlluxioURI dir = new AlluxioURI("/dir");
    mFileSystem.createDirectory(dir);
    int fileCount = 100;
    for (int i = 0; i < fileCount; i++) {
      mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(
          dir, String.format("%05d", i)))).close();
    }
    int batchSize = 2;
    long startAfter = 0;
    for (int i = 0; i < fileCount; i += batchSize) {
      Pair<List<String>, ListStatusPartialResult> result = partialList(dir,
          ListStatusPartialPOptions.newBuilder().setOffsetId(startAfter)
              .setBatchSize(batchSize).build());
      List<String> expectedResult = new ArrayList<>();
      for (int j = i; j < Math.min(fileCount, i + batchSize); j++) {
        expectedResult.add(String.format("%05d", j));
      }
      assertEquals(expectedResult, result.getFirst());
      if ((i + batchSize) < fileCount) {
        assertTrue(result.getSecond().isTruncated());
      } else {
        assertFalse(result.getSecond().isTruncated());
      }
      List<URIStatus> listings = result.getSecond().getListings();
      if (!listings.isEmpty()) {
        startAfter = listings.get(listings.size() - 1).getFileId();
      }
    }
  }

  @Test
  public void listStatusPartialStartAfter() throws Exception {
    AlluxioURI dir = new AlluxioURI("/dir");
    mFileSystem.createDirectory(dir);
    dir = dir.join("nested");
    mFileSystem.createDirectory(dir);
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "a"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "b"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "e"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "f"))).close();
    Pair<List<String>, ListStatusPartialResult> result = partialList(dir,
        ListStatusPartialPOptions.newBuilder().setBatchSize(2).build());
    assertEquals(Arrays.asList("a", "b"), result.getFirst());
    assertTrue(result.getSecond().isTruncated());
    result = partialList(dir, ListStatusPartialPOptions.newBuilder()
        .setStartAfter("b").build());
    assertEquals(Arrays.asList("e", "f"), result.getFirst());
    assertFalse(result.getSecond().isTruncated());
  }

  @Test
  public void listStatusPartialPrefix() throws Exception {
    AlluxioURI dir = new AlluxioURI("/dir");
    mFileSystem.createDirectory(dir);
    dir = dir.join("nested");
    mFileSystem.createDirectory(dir);
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "prefix"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "prefix-suffix"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "dif-prefix"))).close();
    mFileSystem.createFile(new AlluxioURI(PathUtils.concatPath(dir, "dif-suffix"))).close();
    Pair<List<String>, ListStatusPartialResult> result = partialList(dir,
        ListStatusPartialPOptions.newBuilder().setPrefix("/prefix").build());
    assertEquals(Arrays.asList("prefix", "prefix-suffix"), result.getFirst());
    assertFalse(result.getSecond().isTruncated());
  }
}
