/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.plugin;

import co.cask.cdap.api.macro.InvalidMacroException;
import co.cask.cdap.api.macro.MacroEvaluator;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

public class TestMacroEvaluator implements MacroEvaluator {

  private final Map<String, String> propertySubstitutions;
  private final Map<String, String> testFunctionSubstitutions;

  public TestMacroEvaluator() {
    this.propertySubstitutions = ImmutableMap.<String, String>builder()
      // property-specific tests
      .put("notype", "Property Macro")
      .put("{}", "brackets")
      .put("macro", "42")
      .put("${escapedMacroLiteral}", "Times")
      .put("escapedMacroLiteral", "SHOULD NOT EVALUATE")
      .put("funTimes", "ahead")
      // circular key
      .put("key", "${key}")
      // simple macro escaping
      .put("simpleEscape", "\\${\\${expansiveHostnameTree}}")
      // advanced macro escaping
      .put("advancedEscape", "${lotsOfEscaping}")
      .put("lotsOfEscaping", "\\${simpleHostnameTree\\${first}\\${filename\\${fileTypeMacro}")
      // expansive macro escaping
      .put("expansiveEscape", "${${\\${macroLiteral\\}}}\\${nothing${simplePath}")
      .put("\\${macroLiteral\\}", "match")
      .put("match", "{dontEvaluate:${firstPortDigit}0\\${NaN-\\${null}\\${\\${\\${nil")
      // escaping of escaping
      .put("filePathMacro", "executable.exe")
      // simple hostname tree
      .put("simpleHostnameTree", "${simpleHostname}/${simplePath}:${simplePort}")
      .put("simpleHostname", "localhost")
      .put("simplePath", "index.html")
      .put("simplePort", "80")
      // advanced hostname tree
      .put("advancedHostnameTree", "${first}/${second}")
      .put("first", "localhost")
      .put("second", "${third}:${sixth}")
      .put("third", "${fourth}${fifth}")
      .put("fourth", "index")
      .put("fifth", ".html")
      .put("sixth", "80")
      // expansive hostname tree
      .put("expansiveHostnameTree", "${hostname}/${path}:${port}")
      .put("hostname", "${one}")
      .put("path", "${two}")
      .put("port", "${three}")
      .put("one", "${host${hostScopeMacro}}")
      .put("hostScopeMacro", "-local")
      .put("host-local", "${l}${o}${c}${a}${l}${hostSuffix}")
      .put("l", "l")
      .put("o", "o")
      .put("c", "c")
      .put("a", "a")
      .put("hostSuffix", "host")
      .put("two", "${filename${fileTypeMacro}}")
      .put("three", "${firstPortDigit}${secondPortDigit}")
      .put("filename", "index")
      .put("fileTypeMacro", "-html")
      .put("filename-html", "index.html")
      .put("filename-php", "index.php")
      .put("firstPortDigit", "8")
      .put("secondPortDigit", "0")
      .build();

    this.testFunctionSubstitutions = ImmutableMap.<String, String>builder()
      // circular key
      .put("key", "${test(key)}")
      // simple macro escaping
      .put("simpleEscape", "\\${test(\\${test(expansiveHostnameTree)})}")
      // advanced macro escaping
      .put("advancedEscape", "${test(lotsOfEscaping)}")
      .put("lotsOfEscaping", "\\${test(simpleHostnameTree)\\${test(first)}\\${test(filename\\${test(fileTypeMacro))}")
      // expansive macro escaping
      .put("expansiveEscape", "${test(${test(\\${test(macroLiteral\\)\\})})}\\${test(nothing)${test(simplePath)}")
      .put("${test(macroLiteral)}", "match")
      .put("match", "{test(dontEvaluate):${test(firstPortDigit)}0\\${test-\\${test(null)}\\${\\${\\${nil")
      // simple hostname tree
      .put("simpleHostnameTree", "${test(simpleHostname)}/${test(simplePath)}:${test(simplePort)}")
      .put("simpleHostname", "localhost")
      .put("simplePath", "index.html")
      .put("simplePort", "80")
      // advanced hostname tree
      .put("advancedHostnameTree", "${test(first)}/${test(second)}")
      .put("first", "localhost")
      .put("second", "${test(third)}:${test(sixth)}")
      .put("third", "${test(fourth)}${test(fifth)}")
      .put("fourth", "index")
      .put("fifth", ".html")
      .put("sixth", "80")
      // expansive hostname tree
      .put("expansiveHostnameTree", "${test(hostname)}/${test(path)}:${test(port)}")
      .put("hostname", "${test(one)}")
      .put("path", "${test(two)}")
      .put("port", "${test(three)}")
      .put("one", "${test(host${test(hostScopeMacro)})}")
      .put("hostScopeMacro", "-local")
      .put("host-local", "${test(l)}${test(o)}${test(c)}${test(a)}${test(l)}${test(hostSuffix)}")
      .put("l", "l")
      .put("o", "o")
      .put("c", "c")
      .put("a", "a")
      .put("hostSuffix", "host")
      .put("two", "${test(filename${test(fileTypeMacro)})}")
      .put("three", "${test(firstPortDigit)}${test(secondPortDigit)}")
      .put("filename", "index")
      .put("fileTypeMacro", "-html")
      .put("filename-html", "index.html")
      .put("filename-php", "index.php")
      .put("firstPortDigit", "8")
      .put("secondPortDigit", "0")
      .build();
  }

  @Override
  public String lookup(String value)  {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("default macros must have an argument provided.");
    }
    String substitution = propertySubstitutions.get(value);
    if (substitution == null) {
      throw new InvalidMacroException(String.format("Macro '%s' not specified.", value));
    }
    String result = propertySubstitutions.get(value);
    return result;
  }

  @Override
  public String evaluate(String macroFunction, String... arguments) {
    if (!macroFunction.equals("test")) {
      throw new InvalidMacroException(String.format("Macro function '%s' not defined.", macroFunction));
    } else if (arguments.length > 1) {
      throw new InvalidMacroException("Test macro function only takes 1 argument.");
    } else {
      String value = arguments[0];
      if (value == null || value.isEmpty()) {
        throw new IllegalArgumentException("default macros must have an argument provided.");
      }
      String substitution = testFunctionSubstitutions.get(value);
      if (substitution == null) {
        throw new InvalidMacroException(String.format("Macro '%s' not specified.", value));
      }
      return testFunctionSubstitutions.get(value);
    }
  }
}
