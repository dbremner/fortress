/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
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
import java.util.List;
import java.util.Collections;

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

import static java.util.Arrays.asList;

public class TemplateGapNodeCreator extends CodeGenerator implements Runnable {

    private TypeName idType = Types.parse("Id", ast);
    private TypeName listIdType = Types.parse("List<Id>", ast);

    public static List<Field> TEMPLATEGAPFIELDS; {
        TEMPLATEGAPFIELDS = asList(
            new Field(Types.parse("ASTNodeInfo", ast), "info", Option.<String>some("NodeFactory.makeASTNodeInfo(NodeFactory.macroSpan)"), false, true, true),
            new Field(idType , "gapId", Option.<String>none(), false, false, true),
            new Field(listIdType , "templateParams", Option.<String>none(), false, false, true)
        );
    }

    public static List<Field> TEMPLATEGAPEXPRFIELDS; {
        TEMPLATEGAPEXPRFIELDS = asList(
            new Field(Types.parse("ExprInfo", ast), "info", Option.<String>some("NodeFactory.makeExprInfo(NodeFactory.macroSpan)"), false, true, true),
            new Field(idType , "gapId", Option.<String>none(), false, false, true),
            new Field(listIdType , "templateParams", Option.<String>none(), false, false, true)
        );
    }

    public static List<Field> TEMPLATEGAPTYPEFIELDS; {
        TEMPLATEGAPTYPEFIELDS = asList(
            new Field(Types.parse("TypeInfo", ast), "info", Option.<String>some("NodeFactory.makeTypeInfo(NodeFactory.macroSpan)"), false, true, true),
            new Field(idType , "gapId", Option.<String>none(), false, false, true),
            new Field(listIdType , "templateParams", Option.<String>none(), false, false, true)
        );
    }

    public TemplateGapNodeCreator(ASTModel ast) {
        super(ast);
    }

    @Override
    public Iterable<Class<? extends CodeGenerator>> dependencies() {
        return new LinkedList<Class<? extends CodeGenerator>>();
    }

    public void run() {
        TypeName templateGapName = Types.parse("TemplateGap", ast);
        List<Pair<NodeType, NodeType>> templateGaps = new LinkedList<Pair<NodeType, NodeType>>();
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
        for (NodeClass nodeClass: ast.classes()) {
            if ( nodeClass.getClass() == NodeClass.class &&
                 ast.isDescendent(abstractNode, nodeClass) &&
                 !nodeClass.name().startsWith("_Ellipses") &&
                 !nodeClass.name().startsWith("_SyntaxTransformation")){
                List<TypeName> interfaces = new LinkedList<TypeName>();
                interfaces.add(templateGapName);
                interfaces.add(Types.parse(nodeClass.name(), ast));
                String infoType;
                List<Field> fields;
                if ( ast.isDescendent(exprNode, nodeClass) ) {
                    infoType = "ExprInfo";
                    fields = TemplateGapNodeCreator.TEMPLATEGAPEXPRFIELDS;
                } else if ( ast.isDescendent(typeNode, nodeClass) ) {
                    infoType = "TypeInfo";
                    fields = TemplateGapNodeCreator.TEMPLATEGAPTYPEFIELDS;
                } else {
                    infoType = "ASTNodeInfo";
                    fields = TemplateGapNodeCreator.TEMPLATEGAPFIELDS;
                }
                fields = Collections.<Field>emptyList();
                TypeName superName = Types.parse(nodeClass.name(), ast);

                NodeType child = new NodeClass("TemplateGap" + ((NodeClass) nodeClass).name(), false, fields, null, interfaces);
                /*
                NodeType templateGap = new TemplateGapClass("TemplateGap"+nodeClass.name(),
                                                            fields, null, interfaces,
                                                            infoType);
                                                            */
                templateGaps.add(new Pair<NodeType, NodeType>(child, nodeClass));
	    }
        }
        for (Pair<NodeType, NodeType> p: templateGaps) {
            // ast.addType(p.first(), false, p.second());
            ast.addTopType(p.first(), false);
        }
    }

    @Override
    public void generateAdditionalCode() {
        // TODO Auto-generated method stub
    }

    @Override
    public void generateClassMembers(TabPrintWriter arg0, NodeClass arg1) {
        // TODO Auto-generated method stub
    }

    @Override
    public void generateInterfaceMembers(TabPrintWriter arg0, NodeInterface arg1) {
        // TODO Auto-generated method stub
    }
}
