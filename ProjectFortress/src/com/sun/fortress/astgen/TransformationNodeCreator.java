/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/

package com.sun.fortress.astgen;

import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;

import edu.rice.cs.astgen.ASTModel;
import edu.rice.cs.astgen.CodeGenerator;
import edu.rice.cs.astgen.Field;
import edu.rice.cs.astgen.NodeClass;
import edu.rice.cs.astgen.NodeInterface;
import edu.rice.cs.astgen.NodeType;
import edu.rice.cs.astgen.TabPrintWriter;
import edu.rice.cs.astgen.Types;
import edu.rice.cs.astgen.Types.TypeName;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;

public class TransformationNodeCreator extends CodeGenerator implements Runnable {

    // public static List<Field> FIELDS;

    static{
        /*
        List<Field> fields = new ArrayList<Field>();
        TypeName transformType = Types.parse( "com.sun.fortress.syntax_abstractions.phases.SyntaxTransformer", ast );
        fields.add( new Field( transformType, "transformation", Option.<String>none(), false, true, true ) );
        */
    }

    public TransformationNodeCreator(ASTModel ast) {
        super(ast);
    }

    @Override
    public Iterable<Class<? extends CodeGenerator>> dependencies() {
        return new LinkedList<Class<? extends CodeGenerator>>();
    }

    public void run() {
        List<Pair<NodeType, NodeType>> all = new LinkedList<Pair<NodeType, NodeType>>();
        NodeType abstractNode;
        NodeType exprNode;
        NodeType typeNode;
        if ( ast.typeForName("AbstractNode").isSome() &&
             ast.typeForName("Expr").isSome() &&
             ast.typeForName("Type").isSome() ) {
            abstractNode = ast.typeForName("AbstractNode").unwrap();
            exprNode     = ast.typeForName("Expr").unwrap();
            typeNode     = ast.typeForName("Type").unwrap();
        } else
            throw new RuntimeException("Fortress.ast does not define AbstractNode/Expr/Type!");
        for ( NodeType n : ast.classes() ){
            if ( n.getClass() == NodeClass.class &&
                 ast.isDescendent(abstractNode, n) ){

                String infoType;
                if ( ast.isDescendent(exprNode, n) ) infoType = "ExprInfo";
                else if ( ast.isDescendent(typeNode, n) ) infoType = "TypeInfo";
                else infoType = "ASTNodeInfo";

                NodeType child = new TransformationNode((NodeClass) n,ast,infoType);
                all.add( new Pair<NodeType,NodeType>( child, n ) );
            }
        }
        for (Pair<NodeType, NodeType> p: all) {
            ast.addType( p.first(), false, p.second() );
        }
    }

    /*
    private NodeType createNode( NodeType parent ){
        List<TypeName> interfaces = new ArrayList<TypeName>();
        interfaces.add( Types.parse("_SyntaxTransformation", ast ) );
        return new NodeClass( "_SyntaxTransformation" + parent.name(), false, FIELDS, Types.parse( parent.name(), ast ), interfaces );
    }
    */

    @Override
    public void generateAdditionalCode() {
    }

    @Override
    public void generateClassMembers(TabPrintWriter arg0, NodeClass arg1) {
    }

    @Override
    public void generateInterfaceMembers(TabPrintWriter arg0, NodeInterface arg1) {
    }
}
