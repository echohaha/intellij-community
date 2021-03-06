// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.options.ex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointChangeListener
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.Configurable.NoScroll
import com.intellij.openapi.options.Configurable.WithEpDependencies
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DefaultProjectFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ClearableLazyValue
import org.jetbrains.annotations.ApiStatus
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import javax.swing.JComponent

/**
 * This class provides logic for handling change in EPs and sends the update signals to the listeners.
 * e.g. EPs can be updated when settings dialog is open: in this case we have to update the UI according to the changes.
 */
@ApiStatus.Experimental
internal class EpBasedConfigurableGroup(private val project: Project?, delegate: Supplier<ConfigurableGroup?>) : NoScroll, MutableConfigurableGroup, Weighted, SearchableConfigurable, Disposable {
  private val value = ClearableLazyValue.createAtomic(delegate)
  private val listeners = CopyOnWriteArrayList<MutableConfigurableGroup.Listener>()
  private val extendableEp: MutableList<ConfigurableWrapper>

  override fun getDisplayName(): String = value.value.displayName

  override fun getConfigurables(): Array<Configurable> = value.value.configurables

  override fun getId(): String {
    val value = value.value
    return if (value is SearchableConfigurable) (value as SearchableConfigurable).id else "root"
  }

  override fun createComponent(): JComponent? = null

  override fun isModified() = false

  @Synchronized
  override fun addListener(listener: MutableConfigurableGroup.Listener) {
    if (listeners.isEmpty()) {
      val project = project ?: DefaultProjectFactory.getInstance().defaultProject
      val epListener = createListener()
      Configurable.APPLICATION_CONFIGURABLE.getPoint(null).addExtensionPointListener(epListener, false, this)
      Configurable.PROJECT_CONFIGURABLE.getPoint(project).addExtensionPointListener(epListener, false, this)
      for (wrapper in extendableEp) {
        val ep = wrapper.extensionPoint
        val area = wrapper.project?.extensionArea ?: ApplicationManager.getApplication().extensionArea
        if (ep.childrenEPName != null) {
          area.getExtensionPointIfRegistered<Any>(ep.childrenEPName)?.addExtensionPointListener(epListener, false, this)
        }
        else if (ep.dynamic) {
          val cast = ConfigurableWrapper.cast(WithEpDependencies::class.java, wrapper)
          if (cast != null) {
            for (it in cast.dependencies) {
              findExtensionPoint(area, it.name).addExtensionPointListener(epListener, false, this)
            }
          }
        }
      }
    }
    listeners.add(listener)
  }

  override fun apply() {}

  override fun getWeight(): Int {
    val value = value.value
    return if (value is Weighted) (value as Weighted).weight else 0
  }

  private fun createListener(): ExtensionPointChangeListener {
    return ExtensionPointChangeListener {
      value.drop()
      extendableEp.clear()
      collect(extendableEp, value.value.configurables)
      
      for (listener in listeners) {
        listener.handleUpdate()
      }
    }
  }

  override fun dispose() {
    value.drop()
    listeners.clear()
  }

  init {
    extendableEp = ArrayList()
    collect(extendableEp, value.value.configurables)
  }
}

private fun findExtensionPoint(area: ExtensionsArea, name: String): ExtensionPoint<Any> {
  if (area.hasExtensionPoint(name)) {
    return area.getExtensionPoint(name)
  }
  else {
    return ApplicationManager.getApplication().extensionArea.getExtensionPoint(name)
  }
}

@ApiStatus.Internal
private fun collect(list: MutableList<ConfigurableWrapper>, configurables: Array<Configurable>) {
  for (configurable in configurables) {
    if (configurable is ConfigurableWrapper) {
      val ep = configurable.extensionPoint
      if (ep.childrenEPName != null || ep.dynamic) {
        list.add(configurable)
      }
    }
    if (configurable !is Configurable.Composite) {
      continue
    }

    var children: Array<Configurable>
    children = try {
      configurable.configurables
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: Throwable) {
      ConfigurableWrapper.LOG.error("Cannot get children $configurable", e)
      continue
    }
    collect(list, children)
  }
}