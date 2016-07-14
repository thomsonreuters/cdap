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

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public final class MacroParser {
  private static final String[] ESCAPED_TOKENS = {"\\${", "\\}", "\\(", "\\)"};
  private static final String[] DOUBLE_ESCAPED_TOKENS = {"\\\\${", "\\\\}", "\\\\(", "\\\\)"};
  private static final Pattern COMMA = Pattern.compile(",");
  private static final int MAX_SUBSTITUTION_DEPTH = 10;

  private MacroParser(){}

  /**
   * Check if str contains a macro.
   * @param str the raw string containing macro syntax.
   * @throws InvalidMacroException on invalid syntax.
   * @return if str contains a macro.
   */
  public static boolean containsMacro(String str) throws InvalidMacroException {
    // used to check for InvalidMacroException
    substituteMacro(str, null);
    return true;
  }

  /**
   * Creates a set of all macros present in a string.
   * @param str the raw string containing macro syntax.
   * @return a set of all macros present in str.
   */
  public static Set<String> getMacros(String str) {
    // TODO: Fix logic because parsing is dependent on substitution (e.g. escaping)
    Set<String> macros = new HashSet<>();
    substituteMacro(str, 0, null, macros);
    return macros;
  }

  /**
   * Substitutes the provided string with a given macro evaluator. Expands macros from right-to-left recursively.
   * @param str the raw string containing macro syntax
   * @param macroEvaluator the evaluator used to substitute macros
   * @return the original string with all macros expanded and substituted
   */
  public static String substituteMacro(String str, MacroEvaluator macroEvaluator) {
    // final string has escapes not directly embedded in macro syntax replaced
    return replaceEscapedSyntax(substituteMacro(str, 0, macroEvaluator, null));
  }

  private static String substituteMacro(String str, int depth, @Nullable MacroEvaluator macroEvaluator,
                                        @Nullable Set<String> macros) {
    if (depth > MAX_SUBSTITUTION_DEPTH) {
      throw new InvalidMacroException(String.format("Failed substituting maco '%s', expansion exceeded %d levels.",
                                                    str, MAX_SUBSTITUTION_DEPTH));
    }
    MacroMetadata macroPosition = findRightmostMacro(str, macroEvaluator, macros);
    while (macroPosition != null) {
      try {
        str = str.substring(0, macroPosition.startIndex) +
          substituteMacro(macroPosition.substitution, depth + 1, macroEvaluator, macros) +
          str.substring(macroPosition.endIndex + 1);
      } catch (InvalidMacroException e) {
        throw new InvalidMacroException(e.getMessage());
      } catch (NullPointerException e) {
        e.printStackTrace();
        return str;
      }
      macroPosition = findRightmostMacro(str, macroEvaluator, macros);
    }
    return str;
  }

  /**
   * Find the rightmost macro in the specified string, ignoring all characters after the specified index.
   * If no macro is found, returns null.
   *
   * @param str the string to find a macro in
   * @return the rightmost macro and its position in the string
   * @throws InvalidMacroException if invalid macro syntax was found. This cannot be thrown if isLenient is true.
   */
  @Nullable
  private static MacroMetadata findRightmostMacro(String str, @Nullable MacroEvaluator macroEvaluator,
                                                  @Nullable Set<String> macros) {
    int startIndex = str.lastIndexOf("${");
    // skip all escaped syntax '\${'
    while (startIndex > 0 && str.charAt(startIndex - 1) == '\\') {
      // allow escaping of escaping syntax '\\${'
      if (startIndex > 1 && str.charAt(startIndex - 2) == '\\') {
        break;
      }
      startIndex = str.substring(0, startIndex - 1).lastIndexOf("${");
    }

    if (startIndex < 0) {
      return null;
    }

    // found "${", now look for enclosing "}" and allow escaping through \}
    int endIndex = str.indexOf('}', startIndex);
    while (endIndex > 0 && str.charAt(endIndex - 1) == '\\') {
      // allow escaping of escaping syntax '\\}'
      if (endIndex > 1 && str.charAt(endIndex - 2) == '\\') {
        break;
      }
      endIndex = str.indexOf('}', endIndex + 1);
    }

    // if none is found, there is not a macro
    if (endIndex < 0) {
      throw new InvalidMacroException(String.format("Could not find enclosing '}' for macro '%s'.",
                                                    str.substring(startIndex)));
    }

    // macroStr = 'macroFunction(macroArguments)' or just 'property'
    String macroStr = str.substring(startIndex + 2, endIndex).trim();
    String arguments = null;

    // look for '(', which indicates there are arguments and skip all escaped syntax '\('
    int argsStartIndex = macroStr.indexOf('(');
    while (argsStartIndex > 0 && str.charAt(argsStartIndex - 1) == '\\') {
      // allow escaping of escaping syntax '\\('
      if (argsStartIndex > 1 && str.charAt(argsStartIndex - 2) == '\\') {
        break;
      }
      argsStartIndex = macroStr.indexOf('(', argsStartIndex);
    }

    // determine whether to use a macro function or a property lookup
    if (argsStartIndex > 0) {
      // if there is no enclosing ')'
      int closingParenIndex = macroStr.lastIndexOf(')');
      while (closingParenIndex > 0 && macroStr.charAt(closingParenIndex - 1) == '\\') {
        if (closingParenIndex > 1 && macroStr.charAt(closingParenIndex - 2) == '\\') {
          break;
        }
        closingParenIndex = macroStr.substring(0, closingParenIndex).lastIndexOf(')');
      }
      if (closingParenIndex < 0 || !macroStr.endsWith(")")) {
        throw new InvalidMacroException(String.format("Could not find enclosing ')' for macro arguments in '%s'.",
                                                      macroStr));
      }
      // arguments and macroFunction are expected to have escapes replaced when being evaluated
      arguments = replaceEscapedSyntax(macroStr.substring(argsStartIndex + 1, macroStr.length() - 1));
      String macroFunction = replaceEscapedSyntax(macroStr.substring(0, argsStartIndex));
      if (macros != null) {
        macros.add(arguments);
      }
      String[] args = COMMA.split(arguments);
      return new MacroMetadata(macroEvaluator != null ? macroEvaluator.evaluate(macroFunction, args) :
                                 replaceEscapedSyntax(macroStr), startIndex, endIndex);
    } else {
      // property is expected to have escapes replaced when being evaluated
      arguments = replaceEscapedSyntax(macroStr);
      if (macros != null) {
        macros.add(arguments);
      }
      return new MacroMetadata(macroEvaluator != null ? macroEvaluator.lookup(arguments) :
                                 replaceEscapedSyntax(macroStr), startIndex, endIndex);
    }
  }

  /**
   * Removes all escaped syntax for all macro syntax symbols: ${, }, (, )
   * @param str the string to replace escaped syntax in
   * @return the string with no escaped syntax
   */
  private static String replaceEscapedSyntax(String str) {
    // TODO: Handle doubly-escaped syntax (e.g. \\${macro} is evaluated)
    for (String token : ESCAPED_TOKENS) {
      str = str.replace(token, token.substring(1));
    }
    return str;
  }

  private static final class MacroMetadata {
    private final String substitution;
    private final int startIndex;
    private final int endIndex;

    private MacroMetadata(String substitution, int startIndex, int endIndex) {
      this.substitution = substitution;
      this.startIndex = startIndex;
      this.endIndex = endIndex;
    }
  }
}
