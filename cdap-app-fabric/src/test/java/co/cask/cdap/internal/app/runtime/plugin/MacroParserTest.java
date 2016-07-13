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
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.omg.CORBA.DynAnyPackage.Invalid;

import java.util.Set;

public class MacroParserTest {

  // Test containsMacro Parsing

  @Test
  public void testContainsUndefinedMacro() {
    assertContainsMacroParsing("${undefined}", true);
  }

  @Test
  public void testContainsExcessivelyEscapedMacro() {
    assertContainsMacroParsing("\\${${macro}}\\${${{\\}}}\\escaped\\${one}${fun${\\${escapedMacroLiteral\\}}}\\no-op",
                               true);
  }

  @Test
  public void testContainsSimpleEscapedMacro() {
    assertContainsMacroParsing("$${{\\}}", true);
  }

  @Test
  public void testContainsConsecutiveMacros() {
    assertContainsMacroParsing("${simpleHostname}/${simplePath}:${simplePort}", true);
  }

  @Test
  public void testContainsManyMacros() {
    assertContainsMacroParsing("${l}${o}${c}${a}${l}${hostSuffix}", true);
  }


  // Test getMacros Parsing

  @Test
  public void testManyMacrosSet() {
    assertGetMacros("${l}${o}${c}${a}${l}${hostSuffix}", "l", "o", "c", "a", "hostSuffix");
  }


  // Test Property Lookup Parsing

  @Test(expected = InvalidMacroException.class)
  public void testUndefinedMacro() throws Invalid {
    assertSubstitution("${undefined}", "");
  }

  @Test
  public void testNoUnnecessaryReplacement() {
    assertSubstitution("\\\\test\\${${macro}}\\${${{\\}}}\\escaped\\${one}${fun${\\${escapedMacroLiteral\\}}}\\no-op",
                       "\\\\test${42}${brackets}\\escaped${one}ahead\\no-op");
  }

  @Test
  public void propertyBracketEscapingTest() throws InvalidMacroException {
    assertSubstitution("$${{\\}}", "$brackets");
  }

  @Test
  public void testRidiculousSyntaxEscaping() throws InvalidMacroException {
    assertSubstitution("\\${${macro}}\\${${{\\}}}\\${one}${fun${\\${escapedMacroLiteral\\}}}",
                       "${42}${brackets}${one}ahead");
  }

  // TODO: Get this working
  @Ignore
  public void testEscapingOfEscaping() throws InvalidMacroException {
    assertSubstitution("\\file\\path\\name\\\\${filePathMacro}", "\\file\\path\\name\\executable.exe");
  }

  @Test(expected = InvalidMacroException.class)
  public void testNonexistentMacro() throws InvalidMacroException {
    assertSubstitution("${test(invalid)}", "");
  }

  @Test(expected = InvalidMacroException.class)
  public void testCircularMacro() throws InvalidMacroException {
    assertSubstitution("${test(key)}", "");
  }

  @Test
  public void testSimpleMacroSyntaxEscaping() throws InvalidMacroException {
    assertSubstitution("${test(simpleEscape)}", "${test(${test(expansiveHostnameTree)})}");
  }

  @Test
  public void testAdvancedMacroSyntaxEscaping() throws InvalidMacroException {
    assertSubstitution("${test(advancedEscape)}",
                       "${test(simpleHostnameTree)${test(first)}${test(filename${test(fileTypeMacro))}");
  }

  @Test
  public void testExpansiveSyntaxEscaping() throws InvalidMacroException {
    assertSubstitution("${test(expansiveEscape)}",
                       "{test(dontEvaluate):80${test-${test(null)}${${${nil${test(nothing)index.html");
  }

  @Test
  public void testSimplePropertyTree() {
    assertSubstitution("${simpleHostnameTree}", "localhost/index.html:80");
  }

  @Test
  public void testAdvancedPropertyTree() throws InvalidMacroException {
    assertSubstitution("${advancedHostnameTree}", "localhost/index.html:80");
  }

  @Test
  public void testExpansivePropertyTree() throws InvalidMacroException {
    assertSubstitution("${expansiveHostnameTree}", "localhost/index.html:80");
  }

/*
  @Test
  public void testRegex() {
    String str = "\\\\${";
    str = str.replaceAll("\\$", "\\");
    System.out.println(str);
  }
*/

  private void assertContainsMacroParsing(String macro, boolean expected) {
    Assert.assertEquals(MacroParser.containsMacro(macro), expected);
  }

  private void assertGetMacros(String macro, String... expected) {
    Set<String> macros = MacroParser.getMacros(macro);
    for (String value : expected) {
      assert(macros.contains(value));
    }
  }

  private void assertSubstitution(String macro, String expected) {
    Assert.assertEquals(expected, MacroParser.substituteMacro(macro, new TestMacroEvaluator()));
  }
}
