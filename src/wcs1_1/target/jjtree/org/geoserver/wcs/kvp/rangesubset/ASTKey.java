/* Generated By:JJTree: Do not edit this line. ASTKey.java */

package org.geoserver.wcs.kvp.rangesubset;

public class ASTKey extends SimpleNode {
  public ASTKey(int id) {
    super(id);
  }

  public ASTKey(RangeSubsetParser p, int id) {
    super(p, id);
  }


  /** Accept the visitor. **/
  public Object jjtAccept(RangeSubsetParserVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }
}