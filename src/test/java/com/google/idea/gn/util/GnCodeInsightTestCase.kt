// Copyright (c) 2020 Google LLC All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.google.idea.gn.util

import com.google.idea.gn.psi.GnFile
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightPlatformCodeInsightTestCase

abstract class GnCodeInsightTestCase : LightPlatformCodeInsightTestCase() {
  override fun getTestDataPath() = "src/test/testData/project/"

  val gnFile: GnFile get() = file as GnFile

  fun getProjectFile(path: String): VirtualFile? =
      project.guessProjectDir()!!.findFileByRelativePath(path)

  fun getProjectPsiFile(path: String): PsiFile? = getProjectFile(
      path)?.let { PsiManager.getInstance(project).findFile(it) }

  fun copyTestFilesByVirtualFile(filter: (VirtualFile) -> Boolean) {
    runWriteAction {
      val projDir = project.guessProjectDir()!!
      VfsUtil.copyDirectory(this, getVirtualFile(""), projDir, filter)
      // Delete any empty directories.
      VfsUtil.visitChildrenRecursively(projDir, object : VirtualFileVisitor<Unit>() {
        override fun visitFile(file: VirtualFile): Boolean {
          return if (file.isDirectory && file.children.isEmpty()) {
            file.delete(this)
            false
          } else {
            true
          }
        }
      })
    }
  }

  fun copyTestFilesByPath(filter: (String) -> Boolean) {
    copyTestFilesByVirtualFile {
      it.isDirectory || filter(VfsUtil.getRelativePath(it, getVirtualFile(""))!!)
    }
  }

  fun copyTestFiles(vararg files: String) {
    val set = files.toSet()
    copyTestFilesByPath {
      set.contains(it)
    }
  }

}