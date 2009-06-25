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

package com.sun.fortress.scala_src.typechecker

//import _root_.java.util.{List => JavaList}
import _root_.java.util.{Map => JavaMap}
import _root_.java.util.{HashMap => JavaHashMap}
import _root_.java.util.{Set => JavaSet}
import edu.rice.cs.plt.tuple.{Option => JavaOption}
//import edu.rice.cs.plt.collect.EmptyRelation
import edu.rice.cs.plt.collect.IndexedRelation
import edu.rice.cs.plt.collect.Relation
//import edu.rice.cs.plt.collect.UnionRelation
//import com.sun.fortress.compiler.IndexBuilder
import com.sun.fortress.compiler.disambiguator.ExprDisambiguator.HierarchyHistory
import com.sun.fortress.compiler.index.CompilationUnitIndex
import com.sun.fortress.compiler.index.{FunctionalMethod => JavaFunctionalMethod}
import com.sun.fortress.compiler.index.Method
//import com.sun.fortress.compiler.index.ObjectTraitIndex
import com.sun.fortress.compiler.index.ProperTraitIndex
import com.sun.fortress.compiler.index.TraitIndex
//import com.sun.fortress.compiler.index.TypeConsIndex
import com.sun.fortress.compiler.typechecker.TypeNormalizer
import com.sun.fortress.compiler.typechecker.StaticTypeReplacer
import com.sun.fortress.compiler.typechecker.TypeAnalyzer
import com.sun.fortress.compiler.typechecker.TypeEnv
//import com.sun.fortress.compiler.Types
import com.sun.fortress.exceptions.InterpreterBug.bug
import com.sun.fortress.exceptions.StaticError
import com.sun.fortress.exceptions.StaticError.errorMsg
import com.sun.fortress.exceptions.TypeError
import com.sun.fortress.exceptions.ProgramError
import com.sun.fortress.exceptions.ProgramError.error
import com.sun.fortress.nodes._
import com.sun.fortress.nodes_util.NodeFactory
//import com.sun.fortress.nodes_util.ExprFactory
import com.sun.fortress.nodes_util.Modifiers
//import com.sun.fortress.nodes_util.NodeUtil
//import com.sun.fortress.nodes_util.OprUtil
import com.sun.fortress.scala_src.typechecker.impls._
//import com.sun.fortress.scala_src.typechecker.ScalaConstraintUtil._
import com.sun.fortress.scala_src.nodes._
//import com.sun.fortress.scala_src.useful.ASTGenHelper._
import com.sun.fortress.scala_src.useful.ErrorLog
import com.sun.fortress.scala_src.useful.Lists._
import com.sun.fortress.scala_src.useful.Options._
import com.sun.fortress.scala_src.useful.Sets._
import com.sun.fortress.scala_src.useful.SExprUtil._
import com.sun.fortress.scala_src.useful.STypesUtil._
import com.sun.fortress.useful.HasAt
//import com.sun.fortress.useful.NI

/* Quesitons
 */
/* Invariants
 * 1. If a subexpression does not have any inferred type,
 *    type checking the subexpression failed.
 */
object STypeCheckerFactory {

  def make(current: CompilationUnitIndex,
           traits: TraitTable,
           env: TypeEnv,
           analyzer: TypeAnalyzer): STypeChecker =
    new STypeChecker(current, traits, env, analyzer, new ErrorLog)
  
  def make(current: CompilationUnitIndex,
           traits: TraitTable,
           env: TypeEnv,
           analyzer: TypeAnalyzer,
           errors: ErrorLog): STypeChecker =
    new STypeChecker(current, traits, env, analyzer, errors)
}

/**
 * A convenient type for an actual type checker instance for use in Java
 * code. This class mixes all the implementation into the abstract base class.
 */
class STypeChecker(current: CompilationUnitIndex,
                   traits: TraitTable,
                   env: TypeEnv,
                   analyzer: TypeAnalyzer,
                   errors: ErrorLog)
    extends STypeCheckerBase(current, traits, env, analyzer, errors)
    with Dispatch with Functionals with Misc

/**
 * The abstract base class for a type checker. This class contains all the
 * basic functionality for use in type checking, including helper methods that
 * are widely used for many different cases. Use the `STypeChecker` class
 * instead for a fully implemented type checker.
 * 
 * The actual type checking is defined abstractly; concrete subclasses or mixed-
 * in traits should provide the actual implementation of type checking each
 * case.
 * 
 * Constructor parameters are marked `protected val` so that the implementing,
 * mixed-in traits can access the fields.
 */
abstract class STypeCheckerBase(protected val current: CompilationUnitIndex,
                                protected val traits: TraitTable,
                                protected val env: TypeEnv,
                                protected val analyzer: TypeAnalyzer,
                                protected val errors: ErrorLog) {

  protected var labelExitTypes: JavaMap[Id, JavaOption[JavaSet[Type]]] =
    new JavaHashMap[Id, JavaOption[JavaSet[Type]]]()

  def extend(newEnv: TypeEnv, newAnalyzer: TypeAnalyzer) =
    STypeCheckerFactory.make(current, traits, newEnv, newAnalyzer, errors)

  def extend(bindings: List[LValue]) =
    STypeCheckerFactory.make(current,
                             traits,
                             env.extendWithLValues(toJavaList(bindings)),
                             analyzer,
                             errors)

  def extend(sparams: List[StaticParam], where: Option[WhereClause]) =
    STypeCheckerFactory.make(current,
                             traits,
                             env.extendWithStaticParams(sparams),
                             analyzer.extend(sparams, where),
                             errors)

  def extend(sparams: List[StaticParam],
             params: Option[List[Param]],
             where: Option[WhereClause]) = params match {
    case Some(ps) =>
      STypeCheckerFactory.make(current,
                               traits,
                               env.extendWithParams(ps)
                                   .extendWithStaticParams(sparams),
                               analyzer.extend(sparams, where),
                               errors)
    case None =>
      STypeCheckerFactory.make(current,
                               traits,
                               env.extendWithStaticParams(sparams),
                               analyzer.extend(sparams, where),
                               errors)
  }

  def extend(id: Id, typ: Type): STypeChecker =
    extend(List[LValue](NodeFactory.makeLValue(id, typ)))

  def extend(ids: List[Id], types: List[Type]): STypeChecker =
    extend(ids.zip(types).map((p:(Id, Type)) => 
        NodeFactory.makeLValue(p._1,p._2)))

  def extend(decl: LocalVarDecl): STypeChecker =
    STypeCheckerFactory.make(current,
                             traits,
                             env.extend(decl),
                             analyzer,
                             errors)

  def extendWithFunctions(methods: Relation[IdOrOpOrAnonymousName,
                                            JavaFunctionalMethod]) =
    STypeCheckerFactory.make(current,
                             traits,
                             env.extendWithFunctions(methods),
                             analyzer,
                             errors)

  def extendWithMethods(methods: Relation[IdOrOpOrAnonymousName,
                                          Method]) =
    STypeCheckerFactory.make(current,
                             traits,
                             env.extendWithMethods(methods),
                             analyzer,
                             errors)

  def extendWithout(declSite: Node, names: JavaSet[Id]) =
    STypeCheckerFactory.make(current,
                             traits,
                             env.extendWithout(declSite, names),
                             analyzer,
                             errors)

  def addSelf(self_type: Type) =
    extend(List[LValue](NodeFactory.makeLValue("self", self_type)))

  protected def signal(msg:String, hasAt:HasAt) =
    errors.signal(msg, hasAt)

  protected def signal(hasAt:HasAt, msg:String) =
    errors.signal(msg, hasAt)

  protected def syntaxError(hasAt:HasAt, msg:String) =
    error(hasAt, msg)

  /**
   * Determine if subtype <: supertype. If false, then the given error message
   * is signaled for the given location.
   */
  protected def isSubtype(subtype: Type,
                          supertype: Type,
                          location: HasAt,
                          error: String): Boolean = {
    val judgement = isSubtype(subtype, supertype)
    if (! judgement) signal(error, location)
    judgement
  }

  /**
   * Determine if subtype <: supertype.
   */
  protected def isSubtype(subtype: Type, supertype: Type): Boolean
    = analyzer.subtype(subtype, supertype).isTrue

  /**
   * Return the conditions for subtype <: supertype to hold.
   */
  protected def checkSubtype(subtype: Type, supertype: Type): ScalaConstraint =
    analyzer.subtype(subtype, supertype).asInstanceOf[ScalaConstraint]

  protected def equivalentTypes(t1: Type, t2: Type): Boolean =
    analyzer.equivalent(t1, t2).isTrue

  protected def normalize(ty: Type): Type =
    TypeNormalizer.normalize(ty)

  /**
   * Replaces the given name with the name it aliases
   * (or leaves it alone if it doesn't alias any thing)
   */
  protected def handleAlias(name: Id, imports: List[Import]): Id = name match {
    case SId(_, Some(api), _) =>

      // Get the alias for `name` from this import, if it exists.
      def getAlias(imp: Import): Option[Id] = imp match {
        case SImportNames(_, _, aliasApi, aliases) if api.equals(aliasApi) =>

          // Get the name from an aliased name.
          def getName(aliasedName: AliasedSimpleName): Option[Id] =
            aliasedName match {
              case SAliasedSimpleName(_, newName, Some(alias))
                if alias.equals(name) => Some(newName.asInstanceOf)
              case _ => None
            }

          // Get the first name that matched.
          aliases.flatMap(getName).firstOption
        case _ => None
      }

      // Get the first name that matched within any import, or return name.
      imports.flatMap(getAlias).firstOption.getOrElse(name)
    case _ => name
  }

  /**
   * Get the TypeEnv that corresponds to this API.
   */
  protected def getEnvFromApi(api: APIName): TypeEnv =
    TypeEnv.make(traits.compilationUnit(api))

  /**
   * Lookup the type of the given name in the proper type environment.
   */
  protected def getTypeFromName(name: Name): Option[Type] = name match {
    case id@SIdOrOpOrAnonymousName(_, Some(api)) =>
      toOption(getEnvFromApi(api).getType(id))
    case id@SIdOrOpOrAnonymousName(_, None) => toOption(env.getType(id))
    case _ => None
  }

  /**
   * Lookup the modifiers of the given name in the proper type environment.
   */
  protected def getModsFromName(name: Name): Option[Modifiers] = name match {
    case id@SIdOrOpOrAnonymousName(_, Some(api)) =>
      toOption(getEnvFromApi(api).mods(id))
    case id@SIdOrOpOrAnonymousName(_, None) => toOption(env.mods(id))
    case _ => None
  }

  def getErrors(): List[StaticError] = errors.errors

  /**
   * Signal an error if the given type is not a trait.
   */
  protected def assertTrait(t: BaseType,
                            msg: String,
                            error_loc: Node) = t match {
    case tt:TraitType => toOption(traits.typeCons(tt.getName)) match {
      case Some(ti) if ti.isInstanceOf[ProperTraitIndex] =>
      case _ => signal(error_loc, msg)
    }
    case SAnyType(info) =>
    case _ => signal(error_loc, msg)
  }

  /**
   * Create an error message that will have type and expected type inserted.
   * There should be no string format operators in the message.
   */
  protected def errorString(message: String): String =
    message + " has type %s, but it must have %s type."

  /**
   * Create an error message that will have type and expected type inserted.
   * There should be no string format operators in either supplied message.
   */
  protected def errorString(first: String, second: String): String =
    first + " has type %s, but " + second + " type is %s."
  
  // TODO: Rewrite this method!
  protected def inheritedMethods(extendedTraits: List[TraitTypeWhere]) = {
    
    // Return all of the methods from super-traits
    def inheritedMethodsHelper(history: HierarchyHistory,
                               extended_traits: List[TraitTypeWhere])
                               : Relation[IdOrOpOrAnonymousName, Method] = {
      var methods = new IndexedRelation[IdOrOpOrAnonymousName, Method](false)
      var done = false
      var h = history
      for ( trait_ <- extended_traits ; if (! done) ) {
        val type_ = trait_.getBaseType
        if ( ! h.hasExplored(type_) ) {
          h = h.explore(type_)
          type_ match {
            case ty@STraitType(_, name, _, params) =>
              toOption(traits.typeCons(name)) match {
                case Some(ti) =>
                  if ( ti.isInstanceOf[TraitIndex] ) {
                    val trait_params = ti.staticParameters
                    val trait_args = ty.getArgs
                    // Instantiate methods with static args
                    val dotted = toSet(ti.asInstanceOf[TraitIndex].dottedMethods).map(t => (t.first, t.second))
                    for ( pair <- dotted ) {
                        methods.add(pair._1,
                                    pair._2.instantiate(trait_params,trait_args).asInstanceOf[Method])
                    }
                    val getters = ti.asInstanceOf[TraitIndex].getters
                    for ( getter <- toSet(getters.keySet) ) {
                        methods.add(getter,
                                    getters.get(getter).instantiate(trait_params,trait_args).asInstanceOf[Method])
                    }
                    val setters = ti.asInstanceOf[TraitIndex].setters
                    for ( setter <- toSet(setters.keySet) ) {
                        methods.add(setter,
                                    setters.get(setter).instantiate(trait_params,trait_args).asInstanceOf[Method])
                    }
                    val paramsToArgs = new StaticTypeReplacer(trait_params, trait_args)
                    val instantiated_extends_types =
                      toList(ti.asInstanceOf[TraitIndex].extendsTypes).map( (t:TraitTypeWhere) =>
                            t.accept(paramsToArgs).asInstanceOf[TraitTypeWhere] )
                    methods.addAll(inheritedMethodsHelper(h, instantiated_extends_types))
                  } else done = true
                case _ => done = true
              }
            case _ => done = true
          }
        }
      }
      methods
    }
    inheritedMethodsHelper(new HierarchyHistory(), extendedTraits)
  }

  /**
   * Identical to the overloading but with an explicitly given list of static
   * parameters.
   */
  protected def staticInstantiation(sargs: List[StaticArg],
                                    sparams: List[StaticParam],
                                    body: Type): Option[Type] = {

    // Check that the args match.
    if (!staticArgsMatchStaticParams(sargs, sparams)) return None

    // Create mapping from parameter names to static args.
    val paramMap = Map(sparams.map(_.getName).zip(sargs): _*)

    // Gets the actual value out of a static arg.
    def sargToVal(sarg: StaticArg): Node = sarg match {
      case sarg:TypeArg => sarg.getTypeArg
      case sarg:IntArg => sarg.getIntVal
      case sarg:BoolArg => sarg.getBoolArg
      case sarg:OpArg => sarg.getName
      case sarg:DimArg => sarg.getDimArg
      case sarg:UnitArg => sarg.getUnitArg
      case _ => bug("unexpected kind of static arg")
    }

    // Replaces all the params with args in a node.
    object staticReplacer extends Walker {
      override def walk(node: Any): Any = node match {
        case n:VarType => paramMap.get(n.getName).map(sargToVal).getOrElse(n)
        // TODO: Check proper name for OpArgs.
        case n:OpArg => paramMap.get(n.getName.getOriginalName).getOrElse(n)
        case n:IntRef => paramMap.get(n.getName).map(sargToVal).getOrElse(n)
        case n:BoolRef => paramMap.get(n.getName).map(sargToVal).getOrElse(n)
        case n:DimRef => paramMap.get(n.getName).map(sargToVal).getOrElse(n)
        case n:UnitRef => paramMap.get(n.getName).map(sargToVal).getOrElse(n)
        case _ => super.walk(node)
      }
    }

    // Get the replaced type and clear out its static params, if any.
    Some(clearStaticParams(staticReplacer(body).asInstanceOf[Type]))
  }

  /**
   * Instantiates a generic type with some static arguments. The static
   * parameters are retrieved from the body type and replaced inside body with
   * their corresponding static arguments. In the end, any static parameters
   * in the replaced type will be cleared.
   *
   * @param args A list of static arguments to apply to the generic type body.
   * @param body The generic type whose static parameters are to be replaced.
   * @return An option of a type identical to body but with every occurrence of
   *         one of its declared static parameters replaced by corresponding
   *         static args. If None, then the instantiation failed.
   */
  protected def staticInstantiation(sargs: List[StaticArg],
                                    body: Type): Option[Type] =
    staticInstantiation(sargs, getStaticParams(body), body)

  /**
   * Determines if the kinds of the given static args match those of the static
   * parameters. In the case of type arguments, the type is checked to be a
   * subtype of the corresponding type parameter's bounds.
   */
  protected def staticArgsMatchStaticParams(args: List[StaticArg],
                                            params: List[StaticParam]): Boolean = {
    
    if (args.length != params.length) return false

    // Match a single pair.
    def argMatchesParam(argAndParam: (StaticArg, StaticParam)): Boolean = {
      val (arg, param) = argAndParam
      (arg, param.getKind) match {
        case (STypeArg(_, argType), SKindType(_)) =>
            toList(param.getExtendsClause).forall((bound:Type) =>
              isSubtype(argType, bound, arg,
                        errorMsg(normalize(argType), " not a subtype of ", normalize(bound))))
        case (SIntArg(_, _), SKindInt(_)) => true
        case (SBoolArg(_, _), SKindBool(_)) => true
        case (SDimArg(_, _), SKindDim(_)) => true
        case (SOpArg(_, _), SKindOp(_)) => true
        case (SUnitArg(_, _), SKindUnit(_)) => true
        case (SIntArg(_, _), SKindNat(_)) => true
        case (_, _) => false
      }
    }

    // Match every pair.
    args.zip(params).forall(argMatchesParam)
  }

  

  // ---------------------------------------------------------------------------
  // ABSTRACT DEFINITIONS ------------------------------------------------------
  
  /**
   * Type check the given node, returning the rewritten node. Either a subclass
   * or mixed-in trait should provide the implementation of type checking an
   * arbitrary node.
   */
  def check(node: Node): Node

  /**
   * Type check an expression, returning the rewritten node. This overloading
   * should only ever be called by the other two overloadings. That is, no cases
   * in the implementation should call this method itself. Either a subclass or
   * mixed-in trait should provide the implementation of type checking an
   * expression.
   *
   * @param expr The expression node to type check.
   * @param expected The expected type of this expression, if there is one.
   *                 This should only be explicitly used when doing type
   *                 inference.
   * @return The rewritten expression node.
   */
  def checkExpr(expr: Expr, expected: Option[Type]): Expr
  
  // ---------------------------------------------------------------------------
  // CONCRETE DEFINITIONS ------------------------------------------------------

  /**
   * Type check the given node. External code must use this method for all type
   * checking.
   * 
   * @param node The node to type check.
   * @return The resulting node, possibly containing new type information.
   */
  def typeCheck(node: Node): Node =
    try {
      check(node)
    }
    catch {
      case e:ProgramError =>
        errors.errors = List[StaticError]()
        errors.signal(e.getOriginalMessage, e.getLoc.unwrap)
        node
    }

  /**
   * Type check an expression and guarantee that its type is substitutable for
   * the expected type. That is, the resulting type should be a subtype of or
   * coerced to the expected type. If this is not the case, signal an error
   * with the given message. This message should have two "%s" string format
   * operators in it; the first is replaced with the actual type and the second
   * with the expected type.
   *
   * @param expr The expression node to type check.
   * @param expected The expected type of this expression.
   * @param message The message for the error if the expression is well-typed
   *                but fails the expected type check. Must contain two %s
   *                format specifiers.
   * @return The rewritten node if the check succeeded. Otherwise, the original
   *         node.
   */
  def checkExpr(expr: Expr,
                expected: Type,
                message: String): Expr = {
    val checkedExpr = checkExpr(expr, Some(expected))
    getType(checkedExpr) match {
      case Some(typ) =>
        isSubtype(typ, expected, expr, message.format(normalize(typ),
                                                      normalize(expected)))
        addType(checkedExpr, typ)
      case _ => expr
    }
  }

  /**
   * This overloading is identical to the one above, except that the expected
   * type is optional. If defined, it calls the overloading above. If undefined,
   * it calls the checkExpr that does not perform any subtype checks.
   */
  def checkExpr(expr: Expr,
                expected: Option[Type],
                message: String): Expr = expected match {
    case Some(t) => checkExpr(expr, t, message)
    case _ => checkExpr(expr)
  }

  /**
   * Type check an expression, returning the rewritten node. This overloading
   * should be called whenever there is no expected type.
   *
   * @param expr The expression node to type check.
   * @return The rewritten expression node.
   */
  def checkExpr(expr: Expr): Expr = checkExpr(expr, None)

}

/**
 * A type checker that doesn't report its errors. Use the tryCheck() and
 * tryCheckExpr() methods instead of check() and checkExpr() to determine if
 * the check failed or not. As soon as any static error is generated, these
 * methods will return None. If they succeed, they return the node wrapped
 * in Some.
 */
class TryChecker(current: CompilationUnitIndex,
                 traits: TraitTable,
                 env: TypeEnv,
                 analyzer: TypeAnalyzer)
    extends STypeChecker(current, traits, env, analyzer, new ErrorLog) {

  /** Throws the TypeError exception with the given info. */
  override protected def signal(msg:String, hasAt:HasAt): Unit =
    throw TypeError.make(msg,hasAt)

  /** Throws the TypeError exception with the given info. */
  override protected def signal(hasAt:HasAt, msg:String): Unit =
    signal(msg, hasAt)

  /** Check the given node; return it if successful, None otherwise. */
  def tryCheck(node: Node): Option[Node] =
    try {
      Some(super.check(node))
    }
    catch {
      case e:StaticError => None
      case e => throw e
    }

  /** Check the given expression; return it if successful, None otherwise. */
  def tryCheckExpr(expr: Expr): Option[Expr] =
    try {
      Some(super.checkExpr(expr))
    }
    catch {
      case e:StaticError => None
      case e => throw e
    }
}
