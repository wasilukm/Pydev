# This program is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation; either version 2 of the License, or (at your option) any later
# version.
#
# This program is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# this program; if not, write to the Free Software Foundation, Inc.,
# 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
""" Copyright (c) 2000-2003 LOGILAB S.A. (Paris, FRANCE).
 http://www.logilab.fr/ -- mailto:contact@logilab.fr

Raw metrics checker :
"""

__revision__ = "$Id: raw_metrics.py,v 1.1 2004-10-26 12:52:30 fabioz Exp $"

import tokenize
if not hasattr(tokenize, 'NL'):
    raise ValueError("tokenize.NL doesn't exist -- tokenize module too old")

from logilab.common.ureports import Table

from logilab.pylint.interfaces import IRawChecker
from logilab.pylint.checkers import BaseRawChecker, CheckerHandler, EmptyReport
from logilab.pylint.reporters import diff_string

 
class RawMetricsChecker(BaseRawChecker, CheckerHandler):
    """does not check anything but gives some raw metrics :                    
    * total number of lines                                                    
    * total number of code lines                                               
    * total number of docstring lines                                          
    * total number of comments lines                                           
    * total number of empty lines                                              
    """
    
    __implements__ = (IRawChecker,)

    # configuration section name
    name = 'metrics'
    # configuration options
    options = ( )

    def __init__(self, linter):
        BaseRawChecker.__init__(self, linter)
        CheckerHandler.__init__(self)
        self.stats = None
        self.reports = (('R0701', 'Raw metrics', self.report_raw_stats),
                        )
        
    def open(self):
        """init statistics"""
        self.stats = self.linter.add_stats(total_lines=0, code_lines=0,
                                           empty_lines=0, docstring_lines=0,
                                           comment_lines=0)
        
    def process_tokens(self, tokens):
        """update stats
        """
        i = 0
        tokens = list(tokens)
        while i < len(tokens):
            i, lines_number, line_type = get_type(tokens, i)
            self.stats['total_lines'] += lines_number
            self.stats[line_type] += lines_number

    def report_raw_stats(self, sect, stats, old_stats):
        """calculate percentage of code / doc / comment / empty
        """
        total_lines = stats['total_lines']
        if not total_lines:
            raise EmptyReport()
        sect.description = '%s lines have been analyzed' % total_lines
        lines = ('type', 'number', '%', 'previous', 'difference')
        for node_type in ('code', 'docstring', 'comment', 'empty'):
            key = node_type + '_lines'
            total = stats[key]
            percent = float(total * 100) / total_lines
            old = old_stats.get(key, None)
            if old is not None:
                diff_str = diff_string(old, total)
            else:
                old, diff_str = 'NC', 'NC'
            lines += (node_type, str(total), '%.2f' % percent,
                      str(old), diff_str)
        sect.append(Table(children=lines, cols=5, rheaders=1))
                    
        
JUNK = (tokenize.NL, tokenize.INDENT, tokenize.NEWLINE, tokenize.ENDMARKER)

def get_type(tokens, start_index):
    """return the line type : docstring, comment, code, empty
    """
    i = start_index
    tok_type = tokens[i][0]
    start = tokens[i][2]
    pos = start
    line_type = None
    while i < len(tokens) and tokens[i][2][0] == start[0]:
        tok_type = tokens[i][0]
        pos = tokens[i][3]
        if line_type is None:
            if tok_type == tokenize.STRING:
                line_type = 'docstring_lines'
            elif tok_type == tokenize.COMMENT:
                line_type = 'comment_lines'
            elif tok_type in JUNK:
                pass
            else:
                line_type = 'code_lines'
        i += 1
        
    if line_type is None:
        line_type = 'empty_lines'
    elif i < len(tokens) and tok_type == tokenize.NEWLINE:
        i += 1
    return i, pos[0] - start[0] + 1, line_type
    

def register(linter):
    """ required method to auto register this checker """
    linter.register_checker(RawMetricsChecker(linter))
                
