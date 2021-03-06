/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/** Unit test for {@link ProjectFilesystem}. */
public class ProjectFilesystemTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testIsFile() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");

    assertTrue(filesystem.isFile("foo/bar.txt"));
    assertFalse(filesystem.isFile("i_do_not_exist"));
    assertFalse("foo/ is a directory, but not an ordinary file", filesystem.isFile("foo"));
  }

  @Test
  public void testMkdirsCanCreateNestedFolders() {
    boolean isSuccess = filesystem.mkdirs("foo/bar/baz");
    assertTrue("Should be able to create nested folders.", isSuccess);
    assertTrue(new File(tmp.getRoot(), "foo/bar/baz").isDirectory());
  }

  @Test(expected = NullPointerException.class)
  public void testReadFirstLineRejectsNullPath() {
    filesystem.readFirstLine(/* pathRelativeToProjectRoot */ null);
  }

  @Test
  public void testReadFirstLineToleratesNonExistentFile() {
    assertEquals(Optional.absent(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineWithEmptyFile() throws IOException {
    File emptyFile = tmp.newFile("foo.txt");
    Files.write(new byte[0], emptyFile);
    assertTrue(emptyFile.isFile());
    assertEquals(Optional.absent(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineFromMultiLineFile() throws IOException {
    File multiLineFile = tmp.newFile("foo.txt");
    Files.write("foo\nbar\nbaz\n", multiLineFile, Charsets.UTF_8);
    assertEquals(Optional.of("foo"), filesystem.readFirstLine("foo.txt"));
  }
}
