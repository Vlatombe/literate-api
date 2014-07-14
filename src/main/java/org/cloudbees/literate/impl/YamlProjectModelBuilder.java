/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc., Amadeus IT Group
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.cloudbees.literate.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.commons.io.IOUtils;
import org.cloudbees.literate.api.v1.ExecutionEnvironment;
import org.cloudbees.literate.api.v1.ProjectModel;
import org.cloudbees.literate.api.v1.ProjectModel.Builder;
import org.cloudbees.literate.api.v1.ProjectModelBuildingException;
import org.cloudbees.literate.api.v1.ProjectModelRequest;
import org.cloudbees.literate.api.v1.vfs.ProjectRepository;
import org.cloudbees.literate.impl.yaml.Language;
import org.cloudbees.literate.spi.v1.ProjectModelBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;

/**
 * A {@link ProjectModelBuilder} that uses a YAML file as the source of its {@link ProjectModel}
 */
@ProjectModelBuilder.Priority(-1000)
public class YamlProjectModelBuilder implements ProjectModelBuilder {

  /**
   * {@inheritDoc}
   */
  //@Override
  public ProjectModel build(ProjectModelRequest request) throws IOException, ProjectModelBuildingException {
    for (String name : markerFiles(request.getBaseName())) {
      if (request.getRepository().isFile(name)) {
        return new Parser(request).parseProjectModel(request.getRepository(), name);

      }
    }
    throw new ProjectModelBuildingException("Not a YAML based literate project");
  }

  /**
   * {@inheritDoc}
   */
  @NonNull
  public Collection<String> markerFiles(@NonNull String basename) {
    return Arrays.asList("." + basename + ".yml", ".travis.yml");
  }

  private static class Parser {

    private final String[] buildIds;
    private final String environmentsId;
    private final String envvarsId;
    private final String languageId;

    public Parser(@NonNull ProjectModelRequest request) {
      this.buildIds = request.getBuildId().split("[, ]");
      this.environmentsId = request.getEnvironmentsId();
      this.envvarsId = request.getEnvvarsId();
      this.languageId = "language";
    }

    /**
     * Returns the list of commands contained in the object given the provided
     * environment
     *
     * @param value       The input model containing the commands
     * @param environment The environment we wish to obtain commands for
     * @return The list of commands applicable for this environment
     */
    @SuppressWarnings("unchecked")
    public static List<String> getCommands(Object value, ExecutionEnvironment environment) {
      List<String> commands = new ArrayList<String>();
      // if a specific environment command is specified and no environment
      // exists,
      // (value instanceof Map and environment is null)
      // the command is ignored
      if (value instanceof Map && environment != null) {
        Map<String, Object> map = (Map<String, Object>) value;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
          if (environment.getLabels().contains(entry.getKey())) {
            commands.addAll(getCommands(entry.getValue(), environment));
          }
        }
      } else if (value instanceof List) {
        for (Object command : (List<Object>) value) {
          commands.addAll(getCommands(command, environment));
        }
      } else if (value instanceof String) {
        commands.add((String) value);
      }
      return commands;
    }

    /**
     * Parses a file into a literate project model
     * @param repository The repository containing the file
     * @param name the name of the file to parse
     * @return A project model built from the given file
     * @throws IOException in case we encounter I/O issue while accessing the repository
     * @throws ProjectModelBuildingException in case the file contains an invalid model
     */
    public ProjectModel parseProjectModel(ProjectRepository repository, String name) throws IOException, ProjectModelBuildingException {
      InputStream stream = repository.get(name);
      try {
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) yaml.load(stream);
        Map<String, Object> decoratedModel = decorateWithLanguage(model, repository);
        return internalBuild(decoratedModel);
      } finally {
        IOUtils.closeQuietly(stream);
      }
    }

    private Map<String, Object> decorateWithLanguage(Map<String, Object> model, ProjectRepository repository) throws IOException {
      String language = (String) model.get(languageId);
      for (Language l : ServiceLoader.load(Language.class, getClass().getClassLoader())) {
        if (l.supported().contains(language)) {
          return l.decorate(model, repository);
        }
      }
      return model;
    }

    private ProjectModel internalBuild(Map<String, Object> model) throws ProjectModelBuildingException {
      Builder builder = ProjectModel.builder();

    List<ExecutionEnvironment> environments = consumeEnvironmentSection(model);
    environments = decorateWithEnvironmentVariables(environments, model);
      builder.addEnvironments(environments);

      Map<ExecutionEnvironment, List<String>> build = new HashMap<ExecutionEnvironment, List<String>>();
      for (String step : buildIds) {
        Object value = model.get(step);
        if (value != null) {
          for (ExecutionEnvironment environment : environments) {
            if (build.containsKey(environment)) {
              build.get(environment).addAll(getCommands(value, environment));
            } else {
              build.put(environment, new ArrayList<String>(getCommands(value, environment)));
            }
          }
        }
      }
      builder.addBuild(build);
      addTasks(builder, model);
      return builder.build();
    }
	
    private List<ExecutionEnvironment> decorateWithEnvironmentVariables(List<ExecutionEnvironment> environments, Map<String, Object> model) {
        List<ExecutionEnvironment> result = new ArrayList<ExecutionEnvironment>();
        Map<String, String> envvars = consumeEnvSection(model);

        for (ExecutionEnvironment executionEnvironment : environments) {
            result.add(executionEnvironment.withVariables(envvars));
        }
        return result;
    }

    private Map<String, String> consumeEnvSection(Map<String, Object> model) {
        Object object = model.get(envvarsId);
        Map<String, String> envvars;
        if (object instanceof String) {
            String string = (String) object;
            envvars = parseGlobalEnv(string);
        } else if (object instanceof Map) {
            Map map = (Map) object;
            Object globalValues = map.get("global");
            envvars = parseGlobalEnv(globalValues);
        } else {
            envvars = Collections.emptyMap();
        }
        return envvars;
    }

    private Map<String, String> parseGlobalEnv(Object globalValues) {
        Map<String, String> result = new HashMap<String, String>();
        if (globalValues instanceof Collection) {
            Collection collection = (Collection) globalValues;
            result = parseGlobalEnv(collection);
        } else if (globalValues instanceof String) {
            String string = (String) globalValues;
            result = parseGlobalEnv(string);
        }
        return result;
    }

    private Map<String, String> parseGlobalEnv(Collection collection) {
        Map<String, String> result = new HashMap<String, String>();
        for (Object object : collection) {
            assert object instanceof String : "Only simple lists of strings are supported";
            String s = (String) object;
            result.putAll(parseGlobalEnv(s));
        }
        return result;
    }

    private Map<String, String> parseGlobalEnv(String s) {
        Map<String, String> result = new HashMap<String, String>();
        String[] split = s.split(" ");
        for (String string : split) {
            String[] split2 = string.split("=", 2);
            assert split2.length == 2 : "Properties must have format KEY=VALUE";
            result.put(split2[0], split2[1]);
        }
        return result;
    }



    /**
     * @param model The input model
     * @return a list of {@link ExecutionEnvironment} parsed from the input
     * model
     */
    private List<ExecutionEnvironment> consumeEnvironmentSection(Map<String, Object> model) {
      Object value = model.get(environmentsId);
      return parseEnvironment(value, 0);
    }

    /**
     * Parse a part of the environment section
     *
     * @param value The current node in the raw Yaml model
     * @param depth The current depth in the model
     * @return A list of {@link ExecutionEnvironment} parsed from the input
     * model
     */
    @SuppressWarnings("unchecked")
    private List<ExecutionEnvironment> parseEnvironment(Object value, int depth) {
      if (value instanceof Map) {
        return parseEnvironment((Map<String, Object>) value, depth);
      } else if (value instanceof List) {
        return parseEnvironment((List<Object>) value, depth);
      } else if (value instanceof String) {
        return Collections.singletonList(new ExecutionEnvironment((String) value));
      }
      return Collections.singletonList(new ExecutionEnvironment());
    }

    private List<ExecutionEnvironment> parseEnvironment(List<Object> list, int depth) {
      List<ExecutionEnvironment> environments = new ArrayList<ExecutionEnvironment>();
      boolean isSimpleList = (depth == 0);
      List<String> simpleList = new ArrayList<String>();
      for (Object string : list) {
        if (string instanceof String) {
          simpleList.add((String) string);
        } else {
          isSimpleList = false;
        }
      }
      if (isSimpleList) {
        environments.add(new ExecutionEnvironment(simpleList));
      } else {
        environments.addAll(parseComplexList(list));
      }
      return environments;
    }

    @SuppressWarnings("unchecked")
    private List<ExecutionEnvironment> parseComplexList(List<Object> list) {
      List<ExecutionEnvironment> output = new ArrayList<ExecutionEnvironment>();
      for (Object environment : list) {
        if (environment instanceof List) {
          output.add(new ExecutionEnvironment((List<String>) environment));
        } else if (environment instanceof String) {
          output.add(new ExecutionEnvironment((String) environment));
        }
      }
      return output;
    }

    private List<ExecutionEnvironment> parseEnvironment(Map<String, Object> map, int depth) {
      List<ExecutionEnvironment> environments = new ArrayList<ExecutionEnvironment>();
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        for (ExecutionEnvironment list : parseEnvironment(entry.getValue(), depth + 1)) {
          environments.add(new ExecutionEnvironment(list, entry.getKey()));
        }
      }
      return environments;
    }

    /**
     * Add tasks to the builder from the input model, excluding some id already picked up through buildIds
     *
     * @param builder  The ProjectModel builder
     * @param model    The raw input model
     */
    private void addTasks(Builder builder, Map<String, Object> model) {
      for (Entry<String, Object> entry : model.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();

        if (!buildIdsContains(key)) {
          builder.addTask(key, getCommands(value, ExecutionEnvironment.any()));
        }
      }
    }

    private boolean buildIdsContains(String value) {
      return Arrays.binarySearch(buildIds,value) >= 0;
    }

  }

}
