/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.eclipse.server;

import java.io.File;
import java.util.logging.Logger;

import org.apache.hadoop.eclipse.Activator;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.jarpackager.IJarExportRunnable;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;


/**
 * Methods for interacting with the jar file containing the 
 * Mapper/Reducer/Driver classes for a MapReduce job.
 */

public class JarModule {
  static Logger log = Logger.getLogger(JarModule.class.getName());

  private final IResource resource;

  public JarModule(IResource resource) {
    this.resource = resource;
  }

  /**
   * Create the jar file containing all the MapReduce job classes.
   */
  public File buildJar(IProgressMonitor monitor) {
    log.fine("Build jar");
    JarPackageData jarrer = new JarPackageData();

    jarrer.setExportJavaFiles(true);
    jarrer.setExportClassFiles(true);
    jarrer.setExportOutputFolders(true);
    jarrer.setOverwrite(true);

    Path path;

    try {
      IJavaProject project = (IJavaProject) resource.getProject().getNature(
          JavaCore.NATURE_ID); // todo(jz)
      // check this is the case before letting this method get called
      Object element = resource.getAdapter(IJavaElement.class);
      IType type = ((ICompilationUnit) element).findPrimaryType();
      jarrer.setManifestMainClass(type);
      path = new Path(new File(Activator.getDefault().getStateLocation()
          .toFile(), resource.getProject().getName() + "_project_hadoop_"
          + resource.getName() + "_" + System.currentTimeMillis() + ".jar")
          .getAbsolutePath());
      jarrer.setJarLocation(path);

      jarrer.setElements(resource.getProject().members(IResource.FILE));
      IJarExportRunnable runnable = jarrer.createJarExportRunnable(null);
      runnable.run(monitor);
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

    return path.toFile();
  }

  public String getName() {
    return resource.getProject().getName() + "/" + resource.getName();
  }

  public static JarModule fromMemento(String memento) {
    return new JarModule(ResourcesPlugin.getWorkspace().getRoot().findMember(
        Path.fromPortableString(memento)));
  }

  public String toMemento() {
    return resource.getFullPath().toPortableString();
  }
}