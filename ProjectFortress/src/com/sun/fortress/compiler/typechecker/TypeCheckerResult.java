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

package com.sun.fortress.compiler.typechecker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.sun.fortress.compiler.StaticPhaseResult;
import com.sun.fortress.compiler.typechecker.SubtypeHistory;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.ASTNode;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.VarType;
import com.sun.fortress.nodes._InferenceVarType;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.useful.Useful;

import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.lambda.Lambda;
import edu.rice.cs.plt.lambda.Lambda2;
import edu.rice.cs.plt.lambda.Predicate;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;

import static com.sun.fortress.exceptions.InterpreterBug.bug;
import static com.sun.fortress.compiler.typechecker.ConstraintFormula.*;

public class TypeCheckerResult extends StaticPhaseResult {
    private Node ast;
    private final Option<Type> type;
    private final ConstraintFormula nodeConstraints;
    private final Map<Pair<Node,Span>, TypeEnv> nodeTypeEnvs;

    private static Pair<ConstraintFormula, Option<StaticError>>
    collectConstraints(Iterable<? extends TypeCheckerResult> results,
            TypeAnalyzer checker, Node ast) {
        SubtypeHistory empty_history = new SubtypeHistory(checker);

        Iterable<ConstraintFormula> constraints =
            IterUtil.map(results, new Lambda<TypeCheckerResult, ConstraintFormula>(){
                public ConstraintFormula value(TypeCheckerResult arg0) {
                    return arg0.nodeConstraints;
                }});

        ConstraintFormula and = ConstraintFormula.bigAnd(constraints, empty_history);

        return Pair.make(and, Option.<StaticError>none());
    }

	private static Map<Pair<Node,Span>, TypeEnv> collectEnvMaps(Iterable<? extends TypeCheckerResult> results) {
		// Take the union of each map from every TypeCheckerResult
		return IterUtil.fold(results,
				             Collections.<Pair<Node,Span>, TypeEnv>emptyMap(),
				             new Lambda2<Map<Pair<Node,Span>, TypeEnv>,TypeCheckerResult,Map<Pair<Node,Span>, TypeEnv>>() {
								public Map<Pair<Node,Span>, TypeEnv> value(
										Map<Pair<Node,Span>, TypeEnv> arg0,
										TypeCheckerResult arg1) {
									return CollectUtil.union(arg0, arg1.nodeTypeEnvs);
								}});
	}

	public Map<_InferenceVarType, Type> getIVarResults() {
		return nodeConstraints.getMap();
	}



	public static TypeCheckerResult compose(Node _ast, Option<Type> _type,
			TypeAnalyzer type_analyzer,
			TypeCheckerResult... results) {

		Pair<ConstraintFormula,Option<StaticError>> constraints_ =
			collectConstraints(Arrays.asList(results),type_analyzer,_ast);

		List<StaticError> errors = CollectUtil.makeList(IterUtil.relax(collectErrors(Arrays.asList(results))));

		if( constraints_.second().isSome() ) {
			errors = Useful.prepend(constraints_.second().unwrap(), errors);
		}

		return new TypeCheckerResult(_ast,
				_type,
				errors,
				constraints_.first(),
				collectEnvMaps(Arrays.asList(results)));
	}

	public static TypeCheckerResult compose(Node _ast, Type _type,
			TypeAnalyzer type_analyzer,
			TypeCheckerResult... results) {
		Pair<ConstraintFormula,Option<StaticError>> constraints_ =
			collectConstraints(Arrays.asList(results),type_analyzer,_ast);

		List<StaticError> errors = CollectUtil.makeList(IterUtil.relax(collectErrors(Arrays.asList(results))));

		if( constraints_.second().isSome() ) {
			errors = Useful.prepend(constraints_.second().unwrap(), errors);
		}

		return new TypeCheckerResult(_ast,
				_type,
				errors,
				constraints_.first(),
				collectEnvMaps(Arrays.asList(results)));
	}

	public static TypeCheckerResult compose(Node _ast,
			TypeAnalyzer type_analyzer,
			TypeCheckerResult... results) {
		Pair<ConstraintFormula,Option<StaticError>> constraints_ =
			collectConstraints(Arrays.asList(results),type_analyzer,_ast);

		List<StaticError> errors = CollectUtil.makeList(IterUtil.relax(collectErrors(Arrays.asList(results))));

		if( constraints_.second().isSome() ) {
			errors = Useful.prepend(constraints_.second().unwrap(), errors);
		}

		return new TypeCheckerResult(_ast,
				Option.<Type>none(),
				errors,
				constraints_.first(),
				collectEnvMaps(Arrays.asList(results)));
	}

    public static TypeCheckerResult compose(Node _ast, TypeAnalyzer type_analyzer, Option<TypeCheckerResult>... results) {
        List<TypeCheckerResult> real_results =
            CollectUtil.makeList(IterUtil.map(IterUtil.filter(Arrays.asList(results), new Predicate<Option<TypeCheckerResult>>(){
                public boolean contains(Option<TypeCheckerResult> arg0) { return arg0.isSome(); }}),
                new Lambda<Option<TypeCheckerResult>,TypeCheckerResult>(){
                    public TypeCheckerResult value(Option<TypeCheckerResult> arg0) {return arg0.unwrap(); }}));
        return compose(_ast, type_analyzer, real_results);
    }

	public static TypeCheckerResult compose(Node _ast,
			TypeAnalyzer type_analyzer,
			List<TypeCheckerResult> results) {
		Pair<ConstraintFormula,Option<StaticError>> constraints_ =
			collectConstraints(results,type_analyzer,_ast);

		List<StaticError> errors = CollectUtil.makeList(IterUtil.relax(collectErrors(results)));

		if( constraints_.second().isSome() ) {
			errors = Useful.prepend(constraints_.second().unwrap(), errors);
		}
		return new TypeCheckerResult(_ast,
				Option.<Type>none(),
				errors,
				constraints_.first(),
				collectEnvMaps(results));
	}

	public static TypeCheckerResult compose(Node _ast,
			Type _type,
			TypeAnalyzer type_analyzer,
			List<TypeCheckerResult> results) {
		Pair<ConstraintFormula,Option<StaticError>> constraints_ =
			collectConstraints(results,type_analyzer,_ast);

		List<StaticError> errors = CollectUtil.makeList(IterUtil.relax(collectErrors(results)));

		if( constraints_.second().isSome() ) {
			errors = Useful.prepend(constraints_.second().unwrap(), errors);
		}
		return new TypeCheckerResult(_ast,
				Option.wrap(_type),
				errors,
				constraints_.first(),
				collectEnvMaps(results));
	}

	public static TypeCheckerResult compose(Node _ast,
			Option<Type> _type,
			TypeAnalyzer type_analyzer,
			List<TypeCheckerResult> results) {
		Pair<ConstraintFormula,Option<StaticError>> constraints_ =
			collectConstraints(results,type_analyzer,_ast);

		List<StaticError> errors = CollectUtil.makeList(IterUtil.relax(collectErrors(results)));

		if( constraints_.second().isSome() ) {
			errors = Useful.prepend(constraints_.second().unwrap(), errors);
		}
		return new TypeCheckerResult(_ast,
				_type,
				errors,
				constraints_.first(),
				collectEnvMaps(results));
	}

	public static TypeCheckerResult compose(Node _ast,
			TypeAnalyzer type_analyzer,
			Option<List<TypeCheckerResult>> results_) {
		if (results_.isSome()) {
			List<TypeCheckerResult> results = results_.unwrap();

			Pair<ConstraintFormula,Option<StaticError>> constraints_ =
				collectConstraints(results,type_analyzer,_ast);

			List<StaticError> errors = CollectUtil.makeList(IterUtil.relax(collectErrors(results)));

			if( constraints_.second().isSome() ) {
				errors = Useful.prepend(constraints_.second().unwrap(), errors);
			}

			return new TypeCheckerResult(_ast,
					Option.<Type>none(),
					errors,
					constraints_.first(),
					collectEnvMaps(results));
		} else {
			return new TypeCheckerResult(_ast);
		}
	}

	/**
	 * Generally compose should be called instead of this method. This method is
	 * only to be called if you no longer care about propagating constraints upwards.
	 */
	 public static TypeCheckerResult addError(TypeCheckerResult result,
			 StaticError s_err) {
		 List<StaticError> errs = new ArrayList<StaticError>();
		 for( StaticError err : result.errors() ) {
			 errs.add(err);
		 }
		 errs.add(s_err);

		 return new TypeCheckerResult(result.ast, result.type(), errs, result.nodeConstraints);
	 }

	/** Takes a TypeCheckerResult and returns a copy with the new AST **/
	public static TypeCheckerResult replaceAST(TypeCheckerResult result, Node _ast, Map<Pair<Node, Span>,TypeEnv> _nodeTypeEnvs){
		return new TypeCheckerResult(_ast, result.type(),result.errors(),result.nodeConstraints, _nodeTypeEnvs);
	}

	/**
	 * Convenience method that calls {@code addNodeTypeEnvEntry} on 'this'
	 */
	public TypeCheckerResult addNodeTypeEnvEntry(Node node, TypeEnv env) {
		return addNodeTypeEnvEntry(this, node, env);
	}

	/**
	 * Add a mapping from a variable-declaring node to the type environment that is in scope at its location.
	 * This method extends {@code result} with the new entry.
	 * @param result The TypeCheckerResult to be extended.
	 * @param node The AST node that declares variables.
	 * @param env The TypeEnv in scope at {@code node}.
	 * @return A newly extended TypeCheckerResult.
	 */
	public static TypeCheckerResult addNodeTypeEnvEntry(TypeCheckerResult result,
                                                            Node node, TypeEnv env) {
            if ( ! ( node instanceof ASTNode ) )
                bug(node, "Only ASTNodes are supported.");
            return addNodeTypeEnvEntries(result, Collections.singletonMap(Pair.make(node, NodeUtil.getSpan((ASTNode)node)), env));
	}

	/**
	 * Add new entries that link variable-declaring nodes to the type environments that are in scope when
	 * the nodes are reached. This new TypeCheckerResult will be the same as {@code result} except that its
	 * map of entries will be extended with the given one.
	 * @param result TypeCheckerResult to extend.
	 * @param entries The entries that should be added.
	 * @return {@code result} extended with {@code entries}.
	 */
	public static TypeCheckerResult addNodeTypeEnvEntries(TypeCheckerResult result,
			                                              Map<Pair<Node,Span>,TypeEnv> entries) {
		return new TypeCheckerResult(result.ast, result.type,
				                     result.errors(), result.nodeConstraints,
				                     CollectUtil.union(result.nodeTypeEnvs,entries));
	}

	public static Option<? extends Node> astFromResult(Option<TypeCheckerResult> result) {
		if( result.isSome() )
			return Option.some(result.unwrap().ast());
		else
			return Option.<Node>none();
	}

	public static Option<? extends List<? extends Node>> astFromResults(Option<List<TypeCheckerResult>> results) {
		if( results.isSome() )
			return Option.some(astFromResults(results.unwrap()));
		else
			return Option.none();
	}

	public static List<? extends Node> astFromResults(List<TypeCheckerResult> results) {
		return Useful.immutableTrimmedList(
		        CollectUtil.makeList(IterUtil.map(results,
		                new Lambda<TypeCheckerResult, Node>(){
		                  public Node value(TypeCheckerResult arg0) {
				    return arg0.ast();
		                }}
		        )));
	}

	public TypeCheckerResult(Node _ast, Type _type,
			Iterable<? extends StaticError> _errors) {
		super(_errors);
		ast = _ast;
		type = Option.wrap(_type);
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast, Type _type,
			Iterable<? extends StaticError> _errors,
			ConstraintFormula c, Map<Pair<Node,Span>, TypeEnv> map) {
		super(_errors);
		ast = _ast;
		type = Option.wrap(_type);
		nodeConstraints = c;
		nodeTypeEnvs = map;
	}

	public TypeCheckerResult(Node _ast, Type _type,
			Iterable<? extends StaticError> _errors,
			ConstraintFormula c) {
		this(_ast,_type,_errors,c,Collections.<Pair<Node,Span>, TypeEnv>emptyMap());
	}

	public TypeCheckerResult(Node _ast,
			Iterable<? extends StaticError> _errors) {
		super(_errors);
		ast = _ast;
		type = Option.none();
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast) {
		super();
		ast = _ast;
		type = Option.none();
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast, Type _type) {
		super();
		ast = _ast;
		type = Option.wrap(_type);
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast, Option<Type> _type) {
		super();
		ast = _ast;
		type = _type;
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast, Option<Type> _type, Iterable<? extends StaticError> _errors) {
		super(_errors);
		ast = _ast;
		type = _type;
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast,
			Option<Type> _type,
			Iterable<? extends StaticError> _errors,
			ConstraintFormula c) {
		this(_ast, _type, _errors, c, Collections.<Pair<Node,Span>, TypeEnv>emptyMap());
	}

	public TypeCheckerResult(Node _ast, StaticError _error) {
		super(IterUtil.make(_error));
		ast = _ast;
		type = Option.none();
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast, Type _type, StaticError _error) {
		super(IterUtil.make(_error));
		ast = _ast;
		type = Option.wrap(_type);
		nodeConstraints = trueFormula();
		nodeTypeEnvs = Collections.emptyMap();
	}

	public TypeCheckerResult(Node _ast, Option<Type> _type, ConstraintFormula _constraints) {
		super();
		ast = _ast;
		type = _type;
		nodeConstraints = _constraints;
		nodeTypeEnvs = Collections.emptyMap();
		// TODO: insert source locations for constraints
	}

	public TypeCheckerResult(Node _ast, Type _type, ConstraintFormula _constraints) {
		super();
		ast = _ast;
		type = Option.wrap(_type);
		nodeConstraints = _constraints;
		nodeTypeEnvs = Collections.emptyMap();
		// TODO: insert source locations for constraints
	}

	public TypeCheckerResult(Node _ast, ConstraintFormula _constraints) {
		super();
		ast = _ast;
		type = Option.none();
		nodeConstraints = _constraints;
		nodeTypeEnvs = Collections.emptyMap();
		// TODO: insert source locations for constraints
	}

	public TypeCheckerResult(Node _ast, Option<Type> _type,
			Iterable<? extends StaticError> errors, ConstraintFormula c,
			Map<Pair<Node,Span>, TypeEnv> collectEnvMaps) {
		super(errors);
		ast = _ast;
		type = _type;
		nodeConstraints = c;
		nodeTypeEnvs = collectEnvMaps;
	}

	public Node ast() { return ast; }
	public Option<Type> type() { return type; }

	public ConstraintFormula getNodeConstraints() {
		return nodeConstraints;
	}

	/**
	 * @return The mapping from type-declaring nodes to the TypeEnv in scope at
	 * that expression.
	 */
	private Map<Pair<Node, Span>, TypeEnv> getNodeTypeEnvs() {
		return nodeTypeEnvs;
	}

    public TypeCheckerOutput getTypeCheckerOutput() {
        return new TypeCheckerOutput(this.getNodeTypeEnvs());
    }

    //Provide a setter so that we can Normalize the AST and keep all of the other state in TypeCheckerResult
    public void setAst(Node _ast){
    	this.ast=_ast;
    }
    
    public TypeCheckerResult removeStaticParamsFromScope(List<StaticParam> staticParams) {
        List<VarType> var_types = new LinkedList<VarType>();
        for( StaticParam static_param : staticParams ) {
            if( NodeUtil.isTypeParam(static_param) ) {
                var_types.add(NodeFactory.makeVarType(NodeUtil.getSpan(static_param), (Id)static_param.getName()));
            }
        }
        return new TypeCheckerResult(this.ast,
                                     this.type,
                                     this.errors(),
                                     this.nodeConstraints.removeTypesFromScope(var_types),
                                     this.nodeTypeEnvs);
    }
}
