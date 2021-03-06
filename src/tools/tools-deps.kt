/*
 * Copyright 2016-present Greg Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.intellij.clojure.tools

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.*
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.SafeFileOutputStream
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.parser.ClojureLexer
import org.intellij.clojure.psi.ClojureTypes
import org.intellij.clojure.util.*
import java.io.File
import java.io.PrintWriter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author gregsh
 */
private class ClojureProjectDeps(val project: Project) {
  class PostStartup : StartupActivity {
    override fun runActivity(project: Project) {
      ClojureProjectDeps.getInstance(project).initialize()
    }
  }

  class RootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibrarySourceRoots(project: Project): Set<VirtualFile> {
      if (ApplicationManager.getApplication().isUnitTestMode) return emptySet()
      if (!Repo.path.exists()) return emptySet()
      return ClojureProjectDeps.getInstance(project).allDependencies
    }
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project) = ServiceManager.getService(project, ClojureProjectDeps::class.java)!!
    @JvmStatic
    val LOG = Logger.getInstance(ClojureProjectDeps::class.java)
  }

  val cacheFile = File(PathManager.getSystemPath(), "clojure/deps-${project.locationHash}.txt")
  val mapping: MutableMap<String, List<String>> = ContainerUtil.newConcurrentMap()
  val allDependencies: Set<VirtualFile> get() = mapping.values.jbIt()
      .flatten { it }
      .transform(::gavToJar)
      .notNulls()
      .addAllTo(LinkedHashSet<VirtualFile>())
  var resolveInProgress = AtomicBoolean(true)

  fun reindex() = TransactionGuard.getInstance().submitTransactionLater(project, Runnable {
    WriteAction.run<Exception> {
      ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
      resolveInProgress.set(false)
    }
  })

  fun initialize() {
    if (cacheFile.exists()) {
      try {
        read(cacheFile)
      }
      finally {
        reindex()
      }
    }
    else {
      resolveAllDepsInBackground()
    }
  }

  private fun read(cacheFile: File) {
    LOG.info("reading ${cacheFile.path}")
    mapping.putAll(cacheFile.bufferedReader().useLines { seq ->
      val map = HashMap<String, List<String>>()
      var file = ""
      val list = ArrayList<String>()
      for (line in seq) {
        val trimmed = line.trimEnd()
        if (trimmed.endsWith("]")) list.add(trimmed)
        else if (trimmed.endsWith(".clj")) {
          if (file != "") map.put(file, ArrayList(list))
          file = trimmed
          list.clear()
        }
      }
      if (file != "") map.put(file, ArrayList(list))
      map
    })
  }

  private fun write(cacheFile: File) {
    LOG.info("writing ${cacheFile.path}")
    cacheFile.mkdirs()
    PrintWriter(SafeFileOutputStream(cacheFile)).use {
      for ((key, value) in TreeMap(mapping)) {
        it.println(key)
        for (s in value) {
          it.println(s)
        }
      }
    }
  }

  fun resolveAllDepsInBackground() {
    resolveDepsInBackground { mapping.clear(); allProjectFiles(project) }
  }

  fun resolveDepsInBackground(filesGetter: () -> Collection<File>) {
    resolveInProgress.set(true)
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Resolving project dependencies...", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        DumbService.getInstance(myProject).waitForSmartMode()

        val files: Collection<File> = filesGetter()
        for ((index, file) in files.withIndex()) {
          indicator.isIndeterminate = false
          indicator.fraction = (100.0 * index / files.size)
          indicator.text = file.path
          val tool = Tool.choose(file) ?: continue
          mapping[file.path] = ArrayList<String>().apply { collectDeps(tool, this, file.parentFile.path) }
        }
      }

      override fun onFinished() {
        try {
          write(cacheFile)
        }
        finally {
          reindex()
        }
      }
    })
  }
}

private fun collectDeps(tool: Tool, result: ArrayList<String>, path: String): Unit {
  val process = OSProcessHandler(tool.getDeps().withWorkDirectory(path).withCharset(CharsetToolkit.UTF8_CHARSET))
  process.addProcessListener(object : ProcessAdapter() {
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
      if (outputType != ProcessOutputTypes.STDOUT) return
      val trimmed = event.text.trimEnd()
      val idx = trimmed.indexOf("[")
      if (idx == -1 || !trimmed.endsWith("]")) return
      result.add(StringUtil.repeat(" ", idx) + trimmed.substring(idx))
    }
  })
  process.startNotify()
  process.waitFor()
}

private fun gavToJar(gav: String) : VirtualFile? {
  val lexer = ClojureLexer(ClojureLanguage)
  lexer.start(gav)
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_SYM) lexer.advance()
  if (lexer.tokenType != ClojureTypes.C_SYM) return null
  val i1 = lexer.tokenStart
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_STRING && !lexer.tokenType.wsOrComment()) lexer.advance()
  val artifact = gav.substring(i1, lexer.tokenStart)
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_STRING) lexer.advance()
  val version = if (lexer.tokenType == ClojureTypes.C_STRING) lexer.tokenText.trim('\"') else ""

  val (path, name) = (if (artifact.contains("/")) artifact else "$artifact/$artifact").let { artifact ->
    val idx = artifact.indexOf("/")
    Pair(artifact.substring(0, idx).replace('.', '/') + artifact.substring(idx), artifact.substring(idx + 1))
  }
  val gavFile = File(Repo.path, "$path/$version/$name-$version.jar")
  if (!(gavFile.exists() && !gavFile.isDirectory)) {
    ClojureProjectDeps.LOG.info("$name:$version dependency not found")
  }
  else {
    val localFS = LocalFileSystem.getInstance()
    val jarFS = JarFileSystem.getInstance()
    val vFile = localFS.refreshAndFindFileByIoFile(gavFile)
    val jarFile = if (vFile == null) null else jarFS.getJarRootForLocalFile(vFile)
    if (jarFile == null) {
      ClojureProjectDeps.LOG.info("$name:$version dependency not found in VFS")
    }
    else {
      return jarFile
    }
  }
  return null
}

private fun allProjectFiles(project: Project) = ReadAction.compute<Collection<File>, RuntimeException> {
  val contentScope = ProjectScope.getContentScope(project)
  FilenameIndex.getFilesByName(project, Lein.projectFile, contentScope, false).iterate()
      .append(FilenameIndex.getFilesByName(project, Boot.projectFile, contentScope, false))
      .transform { it.virtualFile.toIoFile() }
      .toSortedSet()
}

private fun contextProjectFiles(e: AnActionEvent) = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).iterate()
    .filter { Tool.choose(it.name) != null }
    .transform { it.toIoFile() }
    .toSortedSet()

class SyncDepsAction : AnAction() {
  override fun update(e: AnActionEvent) = updateSyncActionImpl(e, false)

  override fun actionPerformed(e: AnActionEvent) {
    val files = contextProjectFiles(e)
    ClojureProjectDeps.getInstance(e.project ?: return).resolveDepsInBackground { files }
  }
}

class SyncAllDepsAction : AnAction() {
  override fun update(e: AnActionEvent) = updateSyncActionImpl(e, true)

  override fun actionPerformed(e: AnActionEvent) {
    ClojureProjectDeps.getInstance(e.project ?: return).resolveAllDepsInBackground()
  }
}

internal fun updateSyncActionImpl(e: AnActionEvent, syncAll: Boolean) {
  val project = e.project
  if (project != null) {
    val busy = ClojureProjectDeps.getInstance(project).resolveInProgress.get()
    val files = contextProjectFiles(e)
    e.presentation.isVisible = !files.isEmpty() || ActionPlaces.isMainMenuOrActionSearch(e.place)
    e.presentation.isEnabled = (!files.isEmpty() || syncAll) && !busy
  }
  else {
    e.presentation.isEnabledAndVisible = false
  }
}
