# -*- coding: utf-8 -*-

# Copyright © 2016 Cask Data, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

"""Simple, inelegant Sphinx extension which adds a directive for a
tabbed block that may be switched between in HTML.

version: 0.1

The directive adds these parameters, all but the first optional:

    :tabs: comma-separated list of tabs
    
    :mapping: comma-separated list of linked-tabs; default lower-case version of :tabs:

    :independent: flag to indicate that this tab set does not link to another tabs
    
    :dependent: name of tab set this tab belongs to; default :mapping:

Separate the blocks with matching comment lines that begine with the text "tabbed-block".
This is required so that comment markers can be inserted in the text between as part of a
block. Tabs must follow in order of the  :tabs: option.

Comment labels are for convenience, and don't need to match. Note example uses a tab label
with a space in it, and is enclosed in quotes. Note that the comma-separated lists must
not have spaces in them (outside of quotes); ie, use "Linux,Windows", not "Linux,
Windows".

The mapping maps a tab that is displayed to the trigger that will display it. For example,
you could have a set of tabs:

    :tabs: "Mac OS X",Windows
    :mapping: linux,windows
    :dependent: linux-windows
    
Clicking on a "Linux" tab in another tab-set would activate the "Mac OS X" tab in this tab
set. The mappings can not use special characters. If a tab uses a special character, a
mapping is required. An error is raised, as it cannot be resolved using the defualts.

If there is only one tab, the node is set to "independent" automatically, as there is
nothing to switch. 

Examples:

.. tabbed-block::
    :tabs: "Linux or OS/X",Windows
    
  .. tabbed-block Linux

    Successfully started the flow 'WhoFlow' of the application 'HelloWorld' with stored runtime arguments '{}'.
    This flow is an example flow from the ``HelloWorld`` example, running on Linux or Mac OS/X.
    
  .. tabbed-block Windows
    
    Successfully started the flow 'WhoFlow' of the application 'HelloWorld' with stored runtime arguments '{}'.
    This flow is an example flow from the ``HelloWorld`` example, running on Windows.
    
Tab sets are either independent or dependent. Independent tabs do not participate in page
or site tab setting. In other words, clicking on a tab does not change any other tabs.
Dependent tabs do. Clicking on the "Linux" tab will change all other tabs to "Linux". You
may need to include a mapping listing the relationship, such as this:

.. tabbed-block::
  :tabs: Linux,Windows,"Distributed CDAP"
  :mapping: Linux,Windows,Linux

    ...
    
This maps the tab "Distributed CDAP" to the other "Linux" tabs on the site. Clicking that
tab would change other tabs to the "linux" tab. (Changing to "linux" from another tab will
cause the first "linux" tab to be selected.)

JavaScript and design of tabs was taken from the Apache Spark Project:
http://spark.apache.org/examples.html

"""

from docutils import nodes
from docutils.parsers.rst import Directive, directives
from sphinx.util.nodes import set_source_info

# Sets the handlers for the tabs used by a particular instance of tabbed block
# Note doubled {{ to pass them through formatting
DEPENDENT_JS_TB = """\
<script type="text/javascript">

$(function {div_name}() {{
  var tabs = {tab_links};
  var mapping = {mapping};
  var tabSetID = {tabSetID};
  for (var i = 0; i < tabs.length; i++) {{
    var tab = tabs[i];
    $("#{div_name} .example-tab-" + tab).click(changeExampleTab(tab, mapping, "{div_name}", tabSetID));
  }}
}});

</script>
"""

# Note doubled {{ to pass them through formatting
INDEPENDENT_JS_TB = """\
<script type="text/javascript">

function change_{div_name}_ExampleTab(tab) {{
  return function(e) {{
    e.preventDefault();
    var scrollOffset = $(this).offset().top - $(document).scrollTop();
    $("#{div_name} .tab-pane").removeClass("active");
    $("#{div_name} .tab-pane-" + tab).addClass("active");
    $("#{div_name} .example-tab").removeClass("active");
    $("#{div_name} .example-tab-" + tab).addClass("active");
    $(document).scrollTop($(this).offset().top - scrollOffset);
  }}
}}

$(function() {{
  var tabs = {tab_links};
  for (var i = 0; i < tabs.length; i++) {{
    var tab = tabs[i];
    $("#{div_name} .example-tab-" + tab).click(change_{div_name}_ExampleTab(tab));
  }}
}});

</script>
"""

DIV_START = """
<div id="{div_name}" class="{class}">
"""

DIV_END = """
</div>
"""

DIV_DIV_END = """
</div>
</div>
"""

NAV_TABS = """
<ul class="nav nav-tabs">
%s</ul>

"""

NAV_TABS_ENTRY = """\
<li class="example-tab example-tab-{tab_link} {active}"><a href="#">{tab_name}</a></li>
"""

TAB_CONTENT_START = """\
<div class="tabbed-block tab-contents">

"""

TAB_CONTENT_ENTRY_START = """\
<div class="tab-pane tab-pane-{tab_link} {active}">
"""

def dequote(s):
    """
    If a string has single or double quotes around it, remove them.
    Make sure the pair of quotes match.
    If a matching pair of quotes is not found, return the string unchanged.
    """
    if (s[0] == s[-1]) and s.startswith(("'", '"')):
        return s[1:-1]
    return s
    
def clean_ascii(s):
    """
    If a string has any non-ASCII characters, replace them with a hyphen.
    """
    s_clean = ''
    for c in s:
        s_clean += c if c.isalnum() else '-'
    return s_clean

def cleaned_string(s):
    """
    De-quote and remove non-ASCII characters.
    """
    return clean_ascii(dequote(s))


class TabbedBlockNode(nodes.Element): pass


class TabbedBlockBaseNode(nodes.Element):
    def __init__(self, id, tabs, tab_labels, node_mapping, dependent):
        super(TabbedBlockBaseNode,self).__init__()
        self.tb_id = id
        self.tb_tabs = tabs
        self.tb_tab_labels = tab_labels
        self.tb_node_mapping = node_mapping
        self.tb_dependent = dependent


class TabbedBlockStartNode(TabbedBlockBaseNode):
    def __init__(self, id, tabs, tab_labels, node_mapping, dependent):
        super(TabbedBlockStartNode,self).__init__(id, tabs, tab_labels, node_mapping, dependent)


class TabbedBlockEndNode(TabbedBlockBaseNode):
    def __init__(self, id, tabs, tab_labels, node_mapping, dependent):
        super(TabbedBlockEndNode,self).__init__(id, tabs, tab_labels, node_mapping, dependent)


class TabbedBlockTabBaseNode(TabbedBlockBaseNode):
    def __init__(self, id, tab_block_id, tabs, tab_labels, node_mapping, dependent):
        super(TabbedBlockTabBaseNode,self).__init__(id, tabs, tab_labels, node_mapping, dependent)
        self.tb_tab_block_id = tab_block_id


class TabbedBlockTabStartNode(TabbedBlockTabBaseNode):
    def __init__(self, id, tab_block_id, tabs, tab_labels, node_mapping, dependent):
        super(TabbedBlockTabStartNode,self).__init__(id, tab_block_id, tabs, tab_labels, node_mapping, dependent)


class TabbedBlockTabEndNode(TabbedBlockTabBaseNode):
    def __init__(self, id, tab_block_id, tabs, tab_labels, node_mapping, dependent):
        super(TabbedBlockTabEndNode,self).__init__(id, tab_block_id, tabs, tab_labels, node_mapping, dependent)


class TabbedBlock(Directive):

    has_content = True
    option_spec = dict(dependent=directives.unchanged_required,
                       independent=directives.flag,
                       mapping=directives.unchanged_required,
                       tabs=directives.unchanged_required,
                       )
                        
    def cleanup_option(self, option, default, ascii_only=False):
        """Removes leading or trailing quotes or double-quotes from a string option."""
        _option = self.options.get(option,'')
        if not _option:
            return default
        else:
            return clean_ascii(dequote(_option)) if ascii_only else dequote(_option)

    def cleanup_options(self, option, default, ascii_only=False, lower=False):
        """
        Removes leading or trailing quotes or double-quotes from a string option list.
        Removes non-ASCII characters if ascii_only true.
        Converts from Unicode to string
        """
        _option = self.options.get(option,'')
        if not _option:
            return default
        else:
            _options = []
            for s in _option.split(","):
                s = dequote(s)
                s = clean_ascii(s) if ascii_only else s
                s = s.lower() if lower else s
                _options.append(str(s))         
            return _options
            
    def run(self):
        node = TabbedBlockNode()
        node.document = self.state.document
        set_source_info(self, node)

        node['independent'] = self.options.has_key('independent')
        node['tabs'] = self.cleanup_options('tabs', '', ascii_only=True, lower=True)
        node['tab_labels'] = self.cleanup_options('tabs', '')
        
        tab_count = len(node['tabs'])
        if tab_count == 1:
            # If only one tab, force to be independent
            node['independent'] = True
        if not node['independent']:
            node['dependent'] = self.cleanup_option('dependent', '-'.join(node['tabs']))
        node['mapping'] = self.cleanup_options('mapping', node['tabs'], ascii_only=True, lower=True)
        if tab_count != len(node['mapping']):
            print "Warning: tabs (%s) don't match mapping (%s)" % (node['tabs'], node['mapping'])
        self.state.nested_parse(self.content, self.content_offset, node, match_titles=1)
        return [node]
        

def process_tabbed_block_nodes(app, doctree, docname):

    env = app.builder.env
    if not hasattr(env, 'tabbed_block_counter'):
        env.tabbed_block_counter = 0
    
    for node in doctree.traverse(TabbedBlockNode):
        tabs = node.get('tabs')
        tab_labels = node.get('tab_labels')
        node_mapping = node.get('mapping')
        dependent = node.get('dependent')

        env.tabbed_block_counter += 1
        tab_block_id = env.tabbed_block_counter
        new_children = []
        tab_id = -1
        new_children.append(TabbedBlockStartNode(tab_block_id, tabs, tab_labels, node_mapping, dependent))
        for child_node in node.children:
            if isinstance(child_node, nodes.comment) and child_node.astext().startswith('tabbed-block'):
                if tab_id > -1:
                    new_children.append(TabbedBlockTabEndNode(tab_id, tab_block_id, tabs, tab_labels, node_mapping, dependent))
                tab_id += 1
                new_children.append(TabbedBlockTabStartNode(tab_id, tab_block_id, tabs, tab_labels, node_mapping, dependent))
            else:
                new_children.append(child_node)
        new_children.append(TabbedBlockTabEndNode(tab_id, tab_block_id, tabs, tab_labels, node_mapping, dependent))
        new_children.append(TabbedBlockEndNode(tab_block_id, tabs, tab_labels, node_mapping, dependent))
        node.replace_self(new_children)
        

def visit_tbs_html(self, node):
    """Visit a Tabbed Block Start node
        self.tb_id = id
        self.tb_tabs = tabs
        self.tb_tab_labels = tab_labels
        self.tb_node_mapping = node_mapping
        self.tb_dependent = dependent   
    """
#     print "visit_tbs_html: %s" % node.tb_id

    tabs = node.tb_tabs
    tab_labels = node.tb_tab_labels
    node_mapping = node.tb_node_mapping
    dependent = node.tb_dependent

    clean_tab_links = []
    mapping = {}

    i = 0
    if node_mapping:
        for m in node_mapping:
            if m in clean_tab_links:
                i += 1
                m = "%s%d" % (m, i)
            clean_tab_links.append(m)
        for i in range(len(clean_tab_links)):
            mapping[clean_tab_links[i]] = node_mapping[i]
    else:
        # Independent tabs use the tabs for the link
        clean_tab_links = tabs
    
    div_name = 'tabbedblock{0}'.format(node.tb_id)
    fill_div_options = {'div_name': div_name}

    if node.get('independent'):
        # Independent node, doesn't participate in clicks with other nodes and has no mapping
        fill_div_options['class'] = 'independent'
        js_options = {'tab_links':clean_tab_links, 'div_name':div_name}
        js_tpl = INDEPENDENT_JS_TB
    else:
        # Dependent node
        fill_div_options['class'] = "dependent-%s" % dependent
        js_options = {'tab_links':clean_tab_links, 
                      'div_name':div_name,
                      'mapping':repr(mapping),
                      'tabSetID':repr(dependent),
                     }
        js_tpl = DEPENDENT_JS_TB    
    javascript = js_tpl.format(**js_options)
    
    div_start = DIV_START.format(**fill_div_options)

    nav_tabs_html = ''
    for index in range(len(tabs)):
        tab_name, tab_link = tab_labels[index], clean_tab_links[index]
        # Always start with first tab active and let javascript script set based on cookie
        tab_options = {'active': 'active' if not index else '',
                       'tab_link': tab_link,
                       'tab_name': tab_name,}
        nav_tabs_html += NAV_TABS_ENTRY.format(**tab_options)
    nav_tabs_html = NAV_TABS % nav_tabs_html
    
    self.body.append("\n<!-- tabbed-block start -->\n")
    self.body.append(javascript + div_start + nav_tabs_html + TAB_CONTENT_START)
    
def visit_tbe_html(self, node):
    """Visit a Tabbed Block End node"""
    # End div tab-contents
    self.body.append(DIV_END)
    # End div tab block
    self.body.append(DIV_END)
    self.body.append("\n<!-- tabbed-block end -->\n")
    
def visit_tbts_html(self, node):
    """Visit a Tabbed Block Tab Start node"""    
    # Always start with first tab active and let javascript script set based on cookie
    tab_options = {'active': 'active' if not node.tb_id else '',
                   'tab_link': node.tb_tabs[node.tb_id],
                   'tab_name': node.tb_tab_labels[node.tb_id],}
    tab_entry_start = TAB_CONTENT_ENTRY_START.format(**tab_options)
    self.body.append(tab_entry_start)

def visit_tbte_html(self, node):
    """Visit a Tabbed Block Tab End node"""
    # End div tab content
    self.body.append(DIV_END)
    
def depart_tb_html(self, node):
    """Depart a Tabbed Block * node. Stub as it does nothing; shared by all Tabbed Block nodes."""
    pass


def setup(app):
    app.add_node(TabbedBlockNode)
    app.add_node(TabbedBlockStartNode, html=(visit_tbs_html, depart_tb_html))
    app.add_node(TabbedBlockEndNode, html=(visit_tbe_html, depart_tb_html))
    app.add_node(TabbedBlockTabStartNode, html=(visit_tbts_html, depart_tb_html))
    app.add_node(TabbedBlockTabEndNode, html=(visit_tbte_html, depart_tb_html))
    app.add_directive('tabbed-block', TabbedBlock)
    app.connect('doctree-resolved', process_tabbed_block_nodes)
