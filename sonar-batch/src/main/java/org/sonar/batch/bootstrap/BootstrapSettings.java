/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2008-2012 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.batch.bootstrap;

import com.google.common.collect.Maps;
import org.codehaus.plexus.util.StringUtils;
import org.sonar.api.batch.bootstrap.ProjectReactor;

import java.util.Map;
import java.util.Properties;

/**
 * @since 2.12
 */
public class BootstrapSettings {
  private Map<String, String> properties;

  public BootstrapSettings(ProjectReactor reactor) {
    init(reactor);
  }

  private void init(ProjectReactor reactor) {
    properties = Maps.newHashMap();

    // order is important -> bottom-up. The last one overrides all the others.
    addProperties(reactor.getRoot().getProperties());
    properties.putAll(System.getenv());
    addProperties(System.getProperties());
  }

  private void addProperties(Properties p) {
    for (Map.Entry<Object, Object> entry : p.entrySet()) {
      if (entry.getValue() != null) {
        properties.put(entry.getKey().toString(), entry.getValue().toString());
      }
    }
  }

  public Map<String, String> getProperties() {
    return properties;
  }

  public String getProperty(String key) {
    return properties.get(key);
  }

  public String getProperty(String key, String defaultValue) {
    return StringUtils.defaultString(properties.get(key), defaultValue);
  }
}
