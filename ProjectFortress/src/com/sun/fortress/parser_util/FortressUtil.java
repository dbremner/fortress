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

/*
 * Utility functions for the Fortress com.sun.fortress.interpreter.parser.
 */

package com.sun.fortress.parser_util;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.OptionVisitor;

import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.nodes_util.SourceLoc;
import com.sun.fortress.nodes_util.ExprFactory;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Modifiers;
import com.sun.fortress.useful.Cons;
import com.sun.fortress.useful.Pair;
import com.sun.fortress.useful.PureList;
import com.sun.fortress.useful.Useful;
import com.sun.fortress.exceptions.ProgramError;

import static com.sun.fortress.exceptions.InterpreterBug.bug;
import static com.sun.fortress.exceptions.ProgramError.error;

public final class FortressUtil {
    public static <T> T syntaxError(Span span, String msg) {
        return ProgramError.<T>error(ExprFactory.makeVoidLiteralExpr(span), msg);
    }

    public static void println(String arg) {
        System.out.println(arg);
    }

    public static List<Decl> emptyDecls() {
        return Collections.<Decl>emptyList();
    }

    public static List<EnsuresClause> emptyEnsuresClauses() {
        return Collections.<EnsuresClause>emptyList();
    }

    public static List<Expr> emptyExprs() {
        return Collections.<Expr>emptyList();
    }

    public static List<Param> emptyParams() {
        return Collections.<Param>emptyList();
    }

    public static List<StaticParam> emptyStaticParams() {
        return Collections.<StaticParam>emptyList();
    }

    public static List<BaseType> emptyTraitTypes() {
        return Collections.<BaseType>emptyList();
    }

    public static List<TraitTypeWhere> emptyTraitTypeWheres() {
        return Collections.<TraitTypeWhere>emptyList();
    }

    public static List<Type> emptyTypes() {
        return Collections.<Type>emptyList();
    }

    private static Effect effect = NodeFactory.makeEffect(NodeFactory.makeSpan("singleton"));

    public static Effect emptyEffect() {
        return effect;
    }

    public static <T> List<T> getListVal(Option<List<T>> o) {
        return o.unwrap(Collections.<T>emptyList());
    }

    public static <U, T extends U> List<U> mkList(T first) {
        List<U> l = new ArrayList<U>();
        l.add(first);
        return l;
    }

    public static <U, T extends U> List<U> mkList(List<T> all) {
        List<U> l = new ArrayList<U>();
        l.addAll(all);
        return l;
    }

    public static <U, T extends U> List<U> mkList(T first, T second) {
        List<U> l = new ArrayList<U>();
        l.add(first);
        l.add(second);
        return l;
    }

    public static <U, T extends U> List<U> mkList(U first, List<T> rest) {
        List<U> l = new ArrayList<U>();
        l.add(first);
        l.addAll(rest);
        return l;
    }

    public static <U, T extends U> List<U> mkList(List<T> rest, U last) {
        List<U> l = new ArrayList<U>();
        l.addAll(rest);
        l.add(last);
        return l;
    }

    public static List<Type> toTypeList(List<BaseType> tys) {
        List<Type> result = new ArrayList<Type>();
        for (BaseType ty : tys) {
            result.add((Type)ty);
        }
        return result;
    }

    public static Option<List<Type>> toTypeList(Option<List<BaseType>> tys) {
        return tys.apply(new OptionVisitor<List<BaseType>, Option<List<Type>>>() {
            public Option<List<Type>> forSome(List<BaseType> l) {
                return Option.<List<Type>>some(new ArrayList<Type>(l));
            }
            public Option<List<Type>> forNone() { return Option.<List<Type>>none(); }
        });
    }

    public static Modifiers noDuplicate(final Span span, Iterable<Modifiers> mods) {
        Modifiers res = Modifiers.None;
        for (Modifiers mod : mods) {
            if (res.containsAny(mod)) {
                syntaxError(span, "Modifier " + mod + " must not occur multiple times");
            }
            res = res.combine(mod);
        }
        return res;
    }

    public static void checkNoWrapped(Option<List<Param>> optParams) {
        if ( optParams.isSome() ) {
            List<Param> params = optParams.unwrap();
            for ( Param param : params ) {
                if (param.getMods().isWrapped()) {
                    syntaxError(param.getSpan(),
                                "The modifier \"wrapped\" cannot " +
                                "appear in an API.");
                }
            }
        }
    }

    /* true is there exists a self parameter in a given parameter list */
    public static boolean isFunctionalMethod(List<Param> params) {
        for (Param p : params) {
            if (p.getName().getText().equals("self")) return true;
        }
        return false;
    }

    public static boolean validRadix(Span span, String radix) {
        String[] all = new String[]{"2","3","4","5","6","7","8","9","10",
                                    "11","12","13","14","15","16"};
        List<String> validRadix = new LinkedList<String>(java.util.Arrays.asList(all));
        if (! validRadix.contains( radix )) {
            syntaxError(span, "Syntax Error: the radix of " +
                        "a numeral must be an integer from 2 to 16.");
            return false;
        } else return true;
    }

    public static boolean validIntLiteral(String numeral) {
        for (int index = 0; index < numeral.length(); index++) {
            if (numeral.charAt(index) == '.')
                return false;
        }
        return true;
    }

    public static void validId(Id name) {
        name.accept(new NodeDepthFirstVisitor_void(){
            public void forIdOnly(Id id){
                if (id.getText().equals("outcome"))
                    syntaxError(id.getSpan(),
                                "Invalid variable name: 'outcome' is a reserved word.");
            }

            public void defaultTemplateGap(TemplateGap g){
                /* nothing */
            }

            public void for_EllipsesIdOnly(_EllipsesId e){
                /* nothing */
            }
        });
    }

    public static void validId(List<? extends LValue> lvs) {
        for (LValue lv : lvs) {
            validId(lv.getName());
        }
    }

    private static boolean allDigits(String s) {
        for (int index = 0; index < s.length(); index++) {
            if ( ! Character.isDigit(s.charAt(index)) )
                return false;
        }
        return true;
    }

    public static boolean validId(String s) {
        String[] words = s.split("_");
        boolean isNumeral = (words.length == 2 &&
                             (NodeUtil.radix2Number(words[1]) != -1 ||
                              allDigits(words[1])));
        return (!FortressUtil.validOp(s) && !s.equals("_") &&
                !isNumeral && !s.equals("SUM") && !s.equals("PROD"));
    }

    private static boolean compoundOp(String s) {
        return (s.length() > 1 && s.endsWith("=")
                && !s.equals("<=") && !s.equals(">=")
                && !s.equals("=/=") && !s.equals("==="));
    }
    private static boolean validOpChar(char c) {
        return (c == '_' || java.lang.Character.isUpperCase(c));
    }
    public static boolean validOp(String s) {
        if (s.equals("juxtaposition") || s.equals("in") || s.equals("per") ||
            s.equals("square") || s.equals("cubic") || s.equals("inverse") ||
            s.equals("squared") || s.equals("cubed"))
            return true;
        if (s.equals("SUM") || s.equals("PROD")) return false;
        int length = s.length();
        if (length < 2 || compoundOp(s)) return false;
        char start = s.charAt(0);
        if (length == 2 && start == s.charAt(1)) return false;
        if (length > 2 && start == s.charAt(1) && s.charAt(2) == '_')
            return false;
        if (start == '_' || s.endsWith("_")) return false;
        for (int i = 0; i < length; i++) {
            if (!validOpChar(s.charAt(i))) return false;
        }
        return true;
    }

    public static void allHaveTypes(List<LValue> vars) {
        for (LValue l : vars) {
            if (l.getIdType().isNone())
                syntaxError(l.getSpan(),
                            "Mutable variables should be declared with their types.");
        }
    }

    public static List<LValue> setMutable(List<LValue> vars) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, true));
        }
        return result;
    }

    public static List<LValue> setMutable(List<LValue> vars, Span span) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, Modifiers.Var));
        }
        return result;
    }

    public static List<LValue> setMods(List<LValue> vars,
                                       Modifiers mods) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, mods));
        }
        return result;
    }

    public static List<LValue> setModsAndMutable(List<LValue> vars,
                                                 Modifiers mods) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, mods, true));
        }
        return result;
    }

    public static List<LValue> setMutableLValue(List<LValue> vars, Span span) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, Modifiers.Var));
        }
        return result;
    }

    public static List<LValue> setType(List<LValue> vars, Type ty) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, ty));
        }
        return result;
    }

    public static List<LValue> setType(List<LValue> vars, List<Type> tys) {
        List<LValue> result = new ArrayList<LValue>();
        int ind = 0;
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, tys.get(ind)));
            ind += 1;
        }
        return result;
    }

    public static List<LValue> setMutableAndType(List<LValue> vars, Type ty) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, ty, true));
        }
        return result;
    }

    public static List<LValue> setMutableAndType(List<LValue> vars, Span span,
                                                 Type ty) {
        List<LValue> result = new ArrayList<LValue>();
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, ty, Modifiers.Var));
        }
        return result;
    }

    public static List<LValue> setMutableAndType(List<LValue> vars,
                                                 List<Type> tys) {
        List<LValue> result = new ArrayList<LValue>();
        int ind = 0;
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, tys.get(ind), true));
            ind += 1;
        }
        return result;
    }

    public static List<LValue> setMutableAndType(List<LValue> vars, Span span,
                                                 List<Type> tys) {
        List<LValue> result = new ArrayList<LValue>();
        int ind = 0;
        for (LValue l : vars) {
            result.add(NodeFactory.makeLValue(l, tys.get(ind), Modifiers.Var));
            ind += 1;
        }
        return result;
    }

    public static List<LValue> ids2Lvs(List<Id> ids, Modifiers mods,
                                       Option<Type> ty, boolean mutable) {
        List<LValue> lvs = new ArrayList<LValue>();
        for (Id id : ids) {
            lvs.add(NodeFactory.makeLValue(id.getSpan(), id, mods, ty, mutable));
        }
        return lvs;
    }

    public static List<LValue> ids2Lvs(List<Id> ids, Modifiers mods,
                                       Type ty, boolean mutable) {
        return ids2Lvs(ids, mods, Option.<Type>some(ty), mutable);
    }

    public static List<LValue> ids2Lvs(List<Id> ids, Type ty,
                                           boolean mutable) {
        return ids2Lvs(ids, Modifiers.None, Option.<Type>some(ty), mutable);
    }

    public static List<LValue> ids2Lvs(List<Id> ids, Modifiers mods) {
        return ids2Lvs(ids, mods, Option.<Type>none(), false);
    }

    public static List<LValue> ids2Lvs(List<Id> ids) {
        return ids2Lvs(ids, Modifiers.None, Option.<Type>none(), false);
    }

    public static List<LValue> ids2Lvs(List<Id> ids, Modifiers mods,
                                           List<Type> tys, boolean mutable) {
        List<LValue> lvs = new ArrayList<LValue>();
        int ind = 0;
        for (Id id : ids) {
            lvs.add(NodeFactory.makeLValue(id.getSpan(), id, mods,
                                           Option.<Type>some(tys.get(ind)),
                                           mutable));
            ind += 1;
        }
        return lvs;
    }

    public static List<LValue> ids2Lvs(List<Id> ids, List<Type> tys,
                                           boolean mutable) {
        return ids2Lvs(ids, Modifiers.None, tys, mutable);
    }

    public static FnDecl mkFnDecl(Span span, Modifiers mods,
                                  FnHeaderFront fhf, FnHeaderClause fhc) {
        Option<List<BaseType>> throws_ = fhc.getThrowsClause();
        Option<WhereClause> where_ = fhc.getWhereClause();
        Option<Contract> contract = fhc.getContractClause();
        return NodeFactory.makeFnDecl(span, mods, fhf.getName(),
                                      fhf.getStaticParams(), fhf.getParams(),
                                      fhc.getReturnType(), throws_, where_,
                                      contract);
    }


    public static FnDecl mkFnDecl(Span span, Modifiers mods,
                                  IdOrOpOrAnonymousName name, List<StaticParam> sparams,
                                  List<Param> params,
                                  FnHeaderClause fhc) {
        Option<List<BaseType>> throws_ = fhc.getThrowsClause();
        Option<WhereClause> where_ = fhc.getWhereClause();
        Option<Contract> contract = fhc.getContractClause();
        return NodeFactory.makeFnDecl(span, mods, name,
                                      sparams, params,
                                      Option.<Type>none(), throws_,
                                      where_, contract);
    }

    public static FnDecl mkFnDecl(Span span, Modifiers mods,
                                  IdOrOpOrAnonymousName name, List<Param> params,
                                  Type ty) {
        return NodeFactory.makeFnDecl(span, mods, name,
                                      emptyStaticParams(), params,
                                      Option.<Type>some(ty));
    }

    public static FnDecl mkFnDecl(Span span, Modifiers mods,
                                 FnHeaderFront fhf,
                                 FnHeaderClause fhc, Expr expr) {
        Option<List<BaseType>> throws_ = fhc.getThrowsClause();
        Option<WhereClause> where_ = fhc.getWhereClause();
        Option<Contract> contract = fhc.getContractClause();
        return NodeFactory.makeFnDecl(span, mods, fhf.getName(),
                                      fhf.getStaticParams(), fhf.getParams(),
                                      fhc.getReturnType(), throws_, where_,
                                      contract, Option.<Expr>some(expr));
    }

    public static FnDecl mkFnDecl(Span span, Modifiers mods, IdOrOpOrAnonymousName name,
                                 List<StaticParam> sparams, List<Param> params,
                                 FnHeaderClause fhc, Expr expr) {
        Option<List<BaseType>> throws_ = fhc.getThrowsClause();
        Option<WhereClause> where_ = fhc.getWhereClause();
        Option<Contract> contract = fhc.getContractClause();
        return NodeFactory.makeFnDecl(span, mods, name,
                                      sparams, params, Option.<Type>none(),
                                      throws_, where_, contract,
                                      Option.<Expr>some(expr));
    }

// let rec multi_dim_cons (expr : expr)
//                        (dim : int)
//                        (multi : multi_dim_expr) : multi_dim_expr =
//   let elem = multi_dim_elem expr in
//   let span = span_two expr multi in
//     match multi.node_data with
//       | `ArrayElement _ ->
//           multi_dim_row span dim [ elem; multi ]
//       | `ArrayElements
//           { node_span = row_span;
//             node_data =
//               { multi_dim_row_dimension = row_dim;
//                 multi_dim_row_elements = elements; } } ->
//           if dim = row_dim
//           then multi_dim_row span dim (elem :: elements)
//           else if dim > row_dim
//           then multi_dim_row span dim [ elem; multi ]
//           else
//             (match elements with
//                | [] -> Errors.internal_error row_span
//                    "empty array/matrix literal"
//                | first::rest ->
//                    multi_dim_row span row_dim
//                      (multi_dim_cons expr dim first :: rest))
    private static ArrayExpr multiDimElement(Expr expr) {
        return ExprFactory.makeArrayElement(expr);
    }
    private static ArrayElements addOneMultiDim(ArrayExpr multi, int dim,
                                              Expr expr){
        Span span = spanTwo(multi, expr);
        ArrayExpr elem = multiDimElement(expr);
        if (multi instanceof ArrayElement) {
            List<ArrayExpr> elems = new ArrayList<ArrayExpr>();
            elems.add(multi);
            elems.add(elem);
            return ExprFactory.makeArrayElements(span, dim, elems);
        } else if (multi instanceof ArrayElements) {
            ArrayElements m = (ArrayElements)multi;
            int _dim = m.getDimension();
            List<ArrayExpr> elements = m.getElements();
            if (dim == _dim) {
                elements.add(elem);
                return ExprFactory.makeArrayElements(span, dim, elements);
            } else if (dim > _dim) {
                List<ArrayExpr> elems = new ArrayList<ArrayExpr>();
                elems.add(multi);
                elems.add(elem);
                return ExprFactory.makeArrayElements(span, dim, elems);
            } else if (elements.size() == 0) {
                return syntaxError(multi.getSpan(),
                                   "Empty array/matrix literal.");
            } else { // if (dim < _dim)
                int index = elements.size()-1;
                ArrayExpr last = elements.get(index);
                elements.set(index, addOneMultiDim(last, dim, expr));
                return ExprFactory.makeArrayElements(span, _dim, elements);
            }
        } else {
            return syntaxError(multi.getSpan(),
                               "ArrayElement or ArrayElements is expected.");
        }
    }
    public static ArrayElements multiDimCons(Expr init,
                                        List<Pair<Integer,Expr>> rest) {
        ArrayExpr _init = multiDimElement(init);
        if (rest.isEmpty()) {
            return bug(init, "multiDimCons: empty rest");
        } else {
            Pair<Integer,Expr> pair = rest.get(0);
            Expr expr = pair.getB();
            List<ArrayExpr> elems = new ArrayList<ArrayExpr>();
            elems.add(_init);
            elems.add(multiDimElement(expr));
            ArrayElements result = ExprFactory.makeArrayElements(spanTwo(_init,expr),
                                                                 pair.getA(), elems);
            for (Pair<Integer,Expr> _pair : rest.subList(1, rest.size())) {
                int _dim   = _pair.getA();
                Expr _expr = _pair.getB();
                Span span = spanTwo(result, _expr);
                result = addOneMultiDim(result, _dim, _expr);
            }
            return result;
        }
    }

    public static ArrayElements finalizeArrayExpr(ArrayElements a) {
        return ExprFactory.makeArrayElements(a, true);
    }

    public static ArrayElements addStaticArgsToArrayExpr(List<StaticArg> sargs,
                                                         ArrayElements a) {
        return ExprFactory.makeArrayElements(a, sargs, true);
    }

// let rec unpasting_cons (span : span)
//                        (one : unpasting)
//                        (sep : int)
//                        (two : unpasting) : unpasting =
//   match two.node_data with
//     | `UnpastingBind _ | `UnpastingNest _ -> unpasting_split span sep [one;two]
//     | `UnpastingSplit split ->
//         (match split.node_data with
//            | { unpasting_split_elems = (head :: tail) as elems;
//                unpasting_split_dim = dim; } ->
//                if sep > dim then unpasting_split span sep [one;two]
//                else if sep < dim then
//                  unpasting_split span dim
//                    (unpasting_cons (span_two one head) one sep head :: tail)
//                else (* sep = dim *)
//                  unpasting_split span dim (one :: elems)
//            | _ -> Errors.internal_error span "Empty unpasting.")
/*
    public static Unpasting unpastingCons(Span span, Unpasting one, int sep,
                                          Unpasting two) {
        List<Unpasting> onetwo = new ArrayList<Unpasting>();
        onetwo.add(one);
        onetwo.add(two);
        if (two instanceof UnpastingBind) {
            return new UnpastingSplit(span, onetwo, sep);
        } else if (two instanceof UnpastingSplit) {
            UnpastingSplit split = (UnpastingSplit)two;
            List<Unpasting> elems = split.getElems();
            if (elems.size() != 0) {
                int dim = split.getDim();
                if (sep > dim) {
                    return new UnpastingSplit(span, onetwo, sep);
                } else if (sep < dim) {
                    Unpasting head = elems.get(0);
                    elems.set(0, unpastingCons(spanTwo(one,head),one,sep,head));
                    return new UnpastingSplit(span, elems, dim);
                } else { // sep = dim
                    elems.add(0, one);
                    return new UnpastingSplit(span, elems, dim);
                }
            } else { // elems.size() == 0
                return syntaxError(two.getSpan(), "Empty unpasting.");
            }
        } else { //    !(two instanceof UnpastingBind)
                 // && !(two instanceof UnpastingSplit)
            return bug(two, "UnpastingBind or UnpastingSplit expected.");
        }
    }
*/

// let join (one : span) (two : span) : span =
//   match one, two with
//     | None, span | span, None -> span
//     | Some (left,_), Some (_,right) -> Some (left,right)

// let span_two (one : 'a node) (two : 'b node) : span =
//   join one.node_span two.node_span

    public static Span spanTwo(ASTNode s1, ASTNode s2) {
        return new Span(s1.getSpan().getBegin(), s2.getSpan().getEnd());
    }

    public static Span spanTwo(Span s1, Span s2) {
        return new Span(s1.getBegin(), s2.getEnd());
    }

// let rec span_all (com.sun.fortress.interpreter.nodes : 'a node list) : span =
//   match com.sun.fortress.interpreter.nodes with
//     | [] -> None
//     | node :: rest -> join node.node_span (span_all rest)
    public static Span spanAll(Object[] nodes, int size) {
        if (size == 0) return new Span();
        else { // size != 0
            return new Span(((ASTNode)Array.get(nodes,0)).getSpan().getBegin(),
                            ((ASTNode)Array.get(nodes,size-1)).getSpan().getEnd());
        }
    }

    public static Span spanAll(Iterable<? extends ASTNode> nodes) {
        if (IterUtil.isEmpty(nodes)) { return new Span(); }
        else {
            return new Span(IterUtil.first(nodes).getSpan().getBegin(),
                            IterUtil.last(nodes).getSpan().getEnd());
        }
    }

    public static Span spanAll(SourceLoc defaultLoc, Iterable<? extends ASTNode> nodes) {
        if (IterUtil.isEmpty(nodes)) { return new Span(defaultLoc, defaultLoc); }
        else {
            return new Span(IterUtil.first(nodes).getSpan().getBegin(),
                            IterUtil.last(nodes).getSpan().getEnd());
        }
    }

// let build_block (exprs : expr list) : expr list =
//   List_aux.foldr
//     (fun e es ->
//        match e.node_data with
//          | `LetExpr (le,[]) -> [{ e with node_data = `LetExpr (le, es) }]
//          | `LetExpr _ -> raise (Failure "misparsed variable introduction!")
//          | _ -> e :: es)
//     exprs
//     []
//
// let do_block (body : expr list) : expr =
//   let span = span_all body in
//     node span (`FlowExpr (node span (`BlockExpr (build_block body))))
    public static Block doBlock(Span span) {
        return ExprFactory.makeBlock(span, emptyExprs());
    }

    public static Block doBlock(List<Expr> exprs) {
        Span span = spanAll(exprs.toArray(new AbstractNode[0]), exprs.size());
        List<Expr> es = new ArrayList<Expr>();
        Collections.reverse(exprs);
        for (Expr e : exprs) {
            if (e instanceof LetExpr) {
                LetExpr _e = (LetExpr)e;
                if (_e.getBody().isEmpty()) {
                    if (_e instanceof LocalVarDecl) {
                        validId(((LocalVarDecl)_e).getLhs());
                    }
                    _e = ExprFactory.makeLetExpr(_e, es);
                    es = mkList((Expr)_e);
                } else {
                    syntaxError(e.getSpan(), "Misparsed variable introduction!");
                }
            } else {
                if (isEquality(e) && !e.isParenthesized())
                    syntaxError(e.getSpan(),
                                "Equality testing expressions should be parenthesized.");
                else es.add(0, e);
            }
        }
        return ExprFactory.makeBlock(span, es);
    }

    private static boolean isEquality(Expr expr) {
        if (expr instanceof ChainExpr) {
            ChainExpr e = (ChainExpr)expr;
            List<Link> links = e.getLinks();
            if (links.size() == 1) {
                IdOrOp op = links.get(0).getOp().getOriginalName();
                return (op instanceof Op && ((Op)op).getText().equals("="));
            } else return false;
        } else return false;
    }

// (* Turn an expr list into a single TightJuxt *)
// let build_primary (p : expr list) : expr =
//   match p with
//     | [e] -> e
//     | _ ->
//         let es = List.rev p in
//           Node.node (span_all es) (`TightJuxt es)
    public static Expr buildPrimary(PureList<Expr> exprs) {
        if (exprs.size() == 1) return ((Cons<Expr>)exprs).getFirst();
        else {
            exprs = exprs.reverse();
            List<Expr> javaList = Useful.immutableTrimmedList(exprs);
            return ExprFactory.makeTightJuxt(spanAll(javaList.toArray(new AbstractNode[0]),
                                                     javaList.size()), javaList);
        }
    }
}
