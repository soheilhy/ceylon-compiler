/*
 * Copyright Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the authors tag. All rights reserved.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License version 2.
 * 
 * This particular file is subject to the "Classpath" exception as provided in the 
 * LICENSE file that accompanied this code.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package com.redhat.ceylon.compiler.java.codegen;

import static com.redhat.ceylon.compiler.java.codegen.AbstractTransformer.NO_PRIMITIVES;
import static com.redhat.ceylon.compiler.java.codegen.AbstractTransformer.TYPE_ARGUMENT;
import static com.redhat.ceylon.compiler.java.codegen.AbstractTransformer.WANT_RAW_TYPE;
import static com.sun.tools.javac.code.Flags.FINAL;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.redhat.ceylon.compiler.java.codegen.AbstractTransformer.BoxingStrategy;
import com.redhat.ceylon.compiler.java.codegen.ExpressionTransformer.TermTransformer;
import com.redhat.ceylon.compiler.java.util.Decl;
import com.redhat.ceylon.compiler.java.util.Strategy;
import com.redhat.ceylon.compiler.java.util.Util;
import com.redhat.ceylon.compiler.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Functional;
import com.redhat.ceylon.compiler.typechecker.model.FunctionalParameter;
import com.redhat.ceylon.compiler.typechecker.model.Getter;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ParameterList;
import com.redhat.ceylon.compiler.typechecker.model.ProducedReference;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.FunctionArgument;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.PositionalArgument;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

abstract class InvocationBuilder {

    protected final AbstractTransformer gen;
    protected final Node node;    
    protected final Tree.Primary primary;
    protected final Declaration primaryDeclaration;
    protected final ProducedType returnType;
    protected final List<JCExpression> primaryTypeArguments;
    protected boolean unboxed;
    protected BoxingStrategy boxingStrategy;
    private final ListBuffer<JCExpression> args = ListBuffer.lb();
    protected final Map<TypeParameter, ProducedType> typeArguments;
    
    protected InvocationBuilder(AbstractTransformer gen, 
            Tree.Primary primary, Declaration primaryDeclaration,
            ProducedType returnType, Node node) {
        this.gen = gen;
        this.primary = primary;
        this.primaryDeclaration = primaryDeclaration;
        this.returnType = returnType;
        this.node = node;
        if (primary instanceof Tree.MemberOrTypeExpression){
            this.typeArguments = ((Tree.MemberOrTypeExpression) primary).getTarget().getTypeArguments();
        } else {
            this.typeArguments = null;
        }
        if (primary instanceof Tree.StaticMemberOrTypeExpression){
            this.primaryTypeArguments = transformTypeArguments(gen, ((Tree.StaticMemberOrTypeExpression)primary).getTypeArguments().getTypeModels());
        } else {
            this.primaryTypeArguments = transformTypeArguments(gen, null);
        }
    }
    
    static final List<JCExpression> transformTypeArguments(
            AbstractTransformer gen,
            java.util.List<ProducedType> typeArguments) {
        List<JCExpression> result = List.<JCExpression> nil();
        if(typeArguments != null){
            for (ProducedType arg : typeArguments) {
                // cancel type parameters and go raw if we can't specify them
                if(gen.willEraseToObject(arg))
                    return List.nil();
                result = result.append(gen.makeJavaType(arg, TYPE_ARGUMENT));
            }
        }
        return result;
    }
    
    /**
     * Makes calls to {@link #appendArgument(JCExpression)} ready for a 
     * call to {@link #build()}
     */
    protected abstract void compute();
    
    /**
     * Appends an argument
     * @param argExpr
     */
    protected final void appendArgument(JCExpression argExpr) {
        this.args.append(argExpr);
    }
    
    public final void setUnboxed(boolean unboxed) {
        this.unboxed = unboxed;
    }

    public final void setBoxingStrategy(BoxingStrategy boxingStrategy) {
        this.boxingStrategy = boxingStrategy;
    }

    protected final Map<TypeParameter, ProducedType> getTypeArguments() {
        return this.typeArguments;
    }
    
    protected JCExpression transformInvocation(JCExpression primaryExpr, String selector) {
        JCExpression actualPrimExpr = transformInvocationPrimary(primaryExpr, selector);
        JCExpression resultExpr;
        if (primary instanceof Tree.BaseTypeExpression) {
            ProducedType classType = (ProducedType)((Tree.BaseTypeExpression)primary).getTarget();
            resultExpr = gen.make().NewClass(null, null, gen.makeJavaType(classType, AbstractTransformer.CLASS_NEW), args.toList(), null);
        } else if (primary instanceof Tree.QualifiedTypeExpression) {
            resultExpr = gen.make().NewClass(actualPrimExpr, null, gen.makeQuotedIdent(selector), args.toList(), null);
        } else {
            if (primaryDeclaration instanceof FunctionalParameter) {
                if (primaryExpr != null) {
                    actualPrimExpr = gen.makeQualIdent(primaryExpr, primaryDeclaration.getName());
                } else {
                    actualPrimExpr = gen.makeUnquotedIdent(primaryDeclaration.getName());
                }
                selector = "$call";
            }
            resultExpr = gen.make().Apply(primaryTypeArguments, gen.makeQuotedQualIdent(actualPrimExpr, selector), args.toList());
        }
        return resultExpr;
    }

    protected JCExpression transformInvocationPrimary(JCExpression primaryExpr,
            String selector) {
        JCExpression actualPrimExpr;
        if (primary instanceof Tree.QualifiedTypeExpression
                && ((Tree.QualifiedTypeExpression)primary).getPrimary() instanceof Tree.BaseTypeExpression) {
            actualPrimExpr = gen.makeSelect(primaryExpr, "this");
        } else {
            actualPrimExpr = primaryExpr;
        }
        return actualPrimExpr;
    }
    
    protected JCExpression makeInvocation(List<JCExpression> argExprs) {
        gen.at(node);
        JCExpression result = gen.expressionGen().transformPrimary(primary, new TermTransformer() {
            @Override
            public JCExpression transform(JCExpression primaryExpr, String selector) {
                return transformInvocation(primaryExpr, selector);
            }
        });
        result = gen.expressionGen().applyErasureAndBoxing(result, returnType, 
                !unboxed, boxingStrategy, returnType);
        return result;
    }

    public final JCExpression build() {
        boolean prevFnCall = gen.expressionGen().isWithinInvocation();
        gen.expressionGen().setWithinInvocation(true);
        try {
            return makeInvocation(args.toList());
        } finally {
            gen.expressionGen().setWithinInvocation(prevFnCall);
        }
    }
    
    public static InvocationBuilder forSuperInvocation(AbstractTransformer gen,
            Tree.InvocationExpression invocation) {
        Declaration primaryDeclaration = ((Tree.MemberOrTypeExpression)invocation.getPrimary()).getDeclaration();
        java.util.List<ParameterList> paramLists = ((Functional)primaryDeclaration).getParameterLists();
        SuperInvocationBuilder builder = new SuperInvocationBuilder(gen,
                invocation,
                paramLists.get(0));
        builder.compute();
        return builder;
    }
    
    public static InvocationBuilder forInvocation(AbstractTransformer gen, 
            final Tree.InvocationExpression invocation) {
        
        Tree.Primary primary = invocation.getPrimary();
        Declaration primaryDeclaration = ((Tree.MemberOrTypeExpression)primary).getDeclaration();
        InvocationBuilder builder;
        if (invocation.getPositionalArgumentList() != null) {
            java.util.List<ParameterList> paramLists = ((Functional)primaryDeclaration).getParameterLists();
            builder = new PositionalInvocationBuilder(gen, 
                    primary, primaryDeclaration,
                    invocation,
                    paramLists.get(0));
        } else if (invocation.getNamedArgumentList() != null) {
            builder = new NamedArgumentInvocationBuilder(gen, 
                    primary, 
                    primaryDeclaration,
                    invocation);
        } else {
            throw new RuntimeException("Illegal State");
        }
        if (primaryDeclaration instanceof FunctionalParameter) {
            // Callables always have boxed return type
            builder.setBoxingStrategy(invocation.getUnboxed() ? BoxingStrategy.UNBOXED : BoxingStrategy.BOXED);
            builder.setUnboxed(false);
        } else {
            builder.setBoxingStrategy(BoxingStrategy.INDIFFERENT);
            builder.setUnboxed(invocation.getUnboxed());
        }
        builder.compute();
        return builder;
    }
    
    public static InvocationBuilder forCallableInvocation(
            AbstractTransformer gen, Tree.Term expr, final ParameterList parameterList) {
        final Tree.MemberOrTypeExpression primary;
        if (expr instanceof Tree.MemberOrTypeExpression) {
            primary = (Tree.MemberOrTypeExpression)expr;
        } else {
            throw new RuntimeException(expr+"");
        }
        TypeDeclaration primaryDeclaration = expr.getTypeModel().getDeclaration();
        CallableInvocationBuilder builder = new CallableInvocationBuilder (
                gen,
                primary,
                primaryDeclaration,
                gen.getCallableReturnType(expr.getTypeModel()),
                expr, 
                parameterList);
        builder.compute();
        return builder;
    }
    
    public static InvocationBuilder forSpecifierInvocation(
            CeylonTransformer gen, Tree.SpecifierExpression specifierExpression,
            Method method) {
        Tree.Primary primary = (Tree.Primary)specifierExpression.getExpression().getTerm();
        InvocationBuilder builder;
        if (primary instanceof Tree.MemberOrTypeExpression
                && ((Tree.MemberOrTypeExpression)primary).getDeclaration() instanceof Functional) {
            Declaration primaryDeclaration = ((Tree.MemberOrTypeExpression)primary).getDeclaration();
            builder = new MethodReferenceSpecifierInvocationBuilder(
                    gen, 
                    primary, 
                    primaryDeclaration,
                    method,
                    specifierExpression);
        } else if (gen.isCeylonCallable(primary.getTypeModel())) {
            builder = new CallableSpecifierInvocationBuilder(
                    gen, 
                    method, 
                    specifierExpression, 
                    primary);
        } else {
            throw new RuntimeException();
        }
        builder.compute();
        return builder;
    }
}

/**
 * An abstract implementation of InvocationBuilder support invocation 
 * via positional arguments. Supports with sequenced arguments but not 
 * defaulted arguments.
 */
abstract class SimpleInvocationBuilder extends InvocationBuilder {

    protected SimpleInvocationBuilder(
            AbstractTransformer gen,
            Tree.Primary primary,
            Declaration primaryDeclaration,
            ProducedType returnType, 
            Node node) {
        super(gen, primary, primaryDeclaration, returnType, node);
    }

    /**
     * Gets the Parameter corresponding to the given argument
     * @param argIndex
     * @return
     */
    protected abstract Parameter getParameter(int argIndex);
    
    /** Gets the number of arguments actually being supplied */
    protected abstract int getNumArguments();

    /**
     * Gets the transformed expression supplying the argument value for the 
     * given argument index
     */
    protected abstract JCExpression getTransformedArgumentExpression(int argIndex);
    
    protected abstract boolean dontBoxSequence();
    
    @Override
    protected final void compute() {
        int numArguments = getNumArguments();
        for (int argIndex = 0; argIndex < numArguments; argIndex++) {
            Parameter parameter = getParameter(argIndex);
            final JCExpression expr;
            if (!parameter.isSequenced()
                    || dontBoxSequence()) {
                expr = this.getTransformedArgumentExpression(argIndex);
            } else {
                // box with an ArraySequence<T>
                List<JCExpression> x = List.<JCExpression>nil();
                for ( ; argIndex < numArguments; argIndex++) {
                    x = x.append(this.getTransformedArgumentExpression(argIndex));
                }
                expr = gen.makeSequenceRaw(x);
            }
            appendArgument(expr);
        }
    }
}

/**
 * InvocationBuilder used for 'normal' method and initializer invocations via 
 * positional arguments. Supports sequenced and defaulted arguments.
 */
class PositionalInvocationBuilder extends SimpleInvocationBuilder {
    
    private final Tree.PositionalArgumentList positional;
    private final java.util.List<Parameter> declaredParameters;
    
    public PositionalInvocationBuilder(
            AbstractTransformer gen, 
            Tree.Primary primary,
            Declaration primaryDeclaration,
            Tree.InvocationExpression invocation,
            ParameterList parameterList) {
        super(gen, primary, primaryDeclaration, invocation.getTypeModel(), invocation);
        positional = invocation.getPositionalArgumentList();
        declaredParameters = parameterList.getParameters();
        
    }
    protected Tree.Expression getArgumentExpression(int argIndex) {
        return positional.getPositionalArguments().get(argIndex).getExpression();
    }
    @Override
    protected JCExpression getTransformedArgumentExpression(int argIndex) {
        PositionalArgument arg = positional.getPositionalArguments().get(argIndex);
        if (arg.getExpression().getTerm() instanceof FunctionArgument) {
            FunctionArgument farg = (FunctionArgument)arg.getExpression().getTerm();
            return gen.expressionGen().transform(farg);
        }
        return transformArg(
                getArgumentExpression(argIndex), 
                getParameter(argIndex));
    }
    
    protected final JCExpression transformArg(Tree.Term expr, Parameter parameter) {
        if (parameter != null) {
            final boolean isRaw = primaryTypeArguments.isEmpty();
            ProducedType type = gen.getTypeForParameter(parameter, isRaw, getTypeArguments());
            return gen.expressionGen().transformExpression(expr, 
                    Util.getBoxingStrategy(parameter), 
                    type);
        } else {
            // Overloaded methods don't have a reference to a parameter
            // so we have to treat them differently. Also knowing it's
            // overloaded we know we're dealing with Java code so we unbox
            ProducedType type = expr.getTypeModel();
            return gen.expressionGen().transformExpression(expr, 
                    BoxingStrategy.UNBOXED, 
                    type);
        }
    }
    protected Parameter getParameter(int argIndex) {
        return positional.getPositionalArguments().get(argIndex).getParameter();
    }
    @Override
    protected int getNumArguments() {
        return positional.getPositionalArguments().size();
    }
    @Override
    protected boolean dontBoxSequence() {
        return positional.getEllipsis() != null;
    }
    protected boolean hasDefaultArgument(int ii) {
        return declaredParameters.get(ii).isDefaulted();
    }
}

/**
 * InvocationBuilder used for constructing invocations of {@code super()}
 * when creating constructors.
 */
class SuperInvocationBuilder extends PositionalInvocationBuilder {
    
    SuperInvocationBuilder(AbstractTransformer gen,
            Tree.InvocationExpression invocation,
            ParameterList parameterList) {
        super(gen, 
                invocation.getPrimary(), 
                ((Tree.MemberOrTypeExpression)invocation.getPrimary()).getDeclaration(),
                invocation,
                parameterList);
    }
    @Override
    protected JCExpression makeInvocation(List<JCExpression> args) {
        gen.at(node);
        JCExpression result = gen.make().Apply(List.<JCExpression> nil(), gen.make().Ident(gen.names()._super), args);
        return result;
    }
}


/**
 * InvocationBuilder for constructing the invocation of a method reference 
 * used when implementing {@code Callable.call()}
 */
class CallableInvocationBuilder extends SimpleInvocationBuilder {
    
    private final java.util.List<Parameter> callableParameters;
    
    private final java.util.List<Parameter> functionalParameters;
    
    public CallableInvocationBuilder(
            AbstractTransformer gen, Tree.MemberOrTypeExpression primary,
            Declaration primaryDeclaration, ProducedType returnType,
            Tree.Term expr, ParameterList parameterList) {
        super(gen, primary, primaryDeclaration, returnType, expr);
        callableParameters = ((Functional)primary.getDeclaration()).getParameterLists().get(0).getParameters();
        functionalParameters = parameterList.getParameters();
        setUnboxed(expr.getUnboxed());
        setBoxingStrategy(BoxingStrategy.BOXED);// Must be boxed because non-primitive return type
    }
    @Override
    protected int getNumArguments() {
        return functionalParameters.size();
    }
    @Override
    protected boolean dontBoxSequence() {
        return getParameter(getNumArguments() - 1).isSequenced();
    }
    @Override
    protected JCExpression getTransformedArgumentExpression(int argIndex) {
        Parameter param = callableParameters.get(argIndex);
        ProducedType argType = primary.getTypeModel().getTypeArgumentList().get(argIndex+1);
        final boolean isRaw = primaryTypeArguments.isEmpty();
        return CallableBuilder.unpickCallableParameter(gen, isRaw, getTypeArguments(), param, argType, argIndex, functionalParameters.size());
    }
    @Override
    protected Parameter getParameter(int index) {
        return functionalParameters.get(index);
    }
}

/**
 * InvocationBuilder for methods specifierd with a method reference. 
 */
class MethodReferenceSpecifierInvocationBuilder extends SimpleInvocationBuilder {
    
    private final Method method;

    public MethodReferenceSpecifierInvocationBuilder(
            AbstractTransformer gen, Tree.Primary primary,
            Declaration primaryDeclaration,
            Method method, Tree.SpecifierExpression node) {
        super(gen, primary, primaryDeclaration, method.getType(), node);
        this.method = method;
        setUnboxed(primary.getUnboxed());
        setBoxingStrategy(method.getUnboxed() ? BoxingStrategy.UNBOXED : BoxingStrategy.BOXED);
    }

    @Override
    protected int getNumArguments() {
        return method.getParameterLists().get(0).getParameters().size();
    }
    
    @Override
    protected JCExpression getTransformedArgumentExpression(int argIndex) {
        Parameter parameter = getParameter(argIndex);
        final boolean isRaw = primaryTypeArguments.isEmpty();
        ProducedType exprType = gen.expressionGen().getTypeForParameter(parameter, isRaw, getTypeArguments());
        Parameter declaredParameter = ((Functional)primaryDeclaration).getParameterLists().get(0).getParameters().get(argIndex);
        JCExpression result = gen.makeQuotedIdent(parameter.getName());
        result = gen.expressionGen().applyErasureAndBoxing(
                result, 
                exprType, 
                !parameter.getUnboxed(), 
                Util.getBoxingStrategy(declaredParameter), 
                declaredParameter.getType());
        return result;
    }

    @Override
    protected Parameter getParameter(int argIndex) {
        return method.getParameterLists().get(0).getParameters().get(argIndex);
    }
    
    @Override
    protected boolean dontBoxSequence() {
        return method.getParameterLists().get(0).getParameters().get(getNumArguments() - 1).isSequenced();
    }

}

/**
 * InvocationBuilder for methods specified with a Callable 
 */
class CallableSpecifierInvocationBuilder extends InvocationBuilder {
    
    private final Method method;
    private final JCExpression callable;
    public CallableSpecifierInvocationBuilder(
            AbstractTransformer gen, 
            Method method, 
            Tree.SpecifierExpression specifierExpression,
            Tree.Term term) {
        super(gen, null, null, method.getType(), term);
        this.callable = gen.expressionGen().transformExpression(term);
        this.method = method;
        // Because we're calling a callable, and they always return a 
        // boxed result
        setUnboxed(false);
        setBoxingStrategy(method.getUnboxed() ? BoxingStrategy.UNBOXED : BoxingStrategy.BOXED);
    }
    @Override
    protected void compute() {
        boolean isRaw = primaryTypeArguments.isEmpty();
        Map<TypeParameter, ProducedType> typeArgumentModels = getTypeArguments();
        int argIndex = 0;
        for(Parameter parameter : method.getParameterLists().get(0).getParameters()) {
            ProducedType exprType = gen.expressionGen().getTypeForParameter(parameter, isRaw, typeArgumentModels);
            Parameter declaredParameter = method.getParameterLists().get(0).getParameters().get(argIndex);
            
            JCExpression result = gen.makeQuotedIdent(parameter.getName());
            
            result = gen.expressionGen().applyErasureAndBoxing(
                    result, 
                    exprType, 
                    !parameter.getUnboxed(), 
                    BoxingStrategy.BOXED,// Callables always have boxed params 
                    declaredParameter.getType());
            appendArgument(result);
            argIndex++;
        }
    }
    @Override
    protected JCExpression makeInvocation(List<JCExpression> args) {
        gen.at(node);
        JCExpression result = gen.make().Apply(primaryTypeArguments, gen.makeQuotedQualIdent(callable, "$call"), args);
        result = gen.expressionGen().applyErasureAndBoxing(result, returnType, 
                !unboxed, boxingStrategy, returnType);
        return result;
    }
}

/**
 * InvocationBuilder for 'normal' method and initializer invocations
 * using named arguments
 */
class NamedArgumentInvocationBuilder extends InvocationBuilder {
    
    private final Tree.NamedArgumentList namedArgumentList;
    private final ListBuffer<JCStatement> vars = ListBuffer.lb();
    private final String callVarName;
    private final String varBaseName;
    private final Set<String> argNames = new HashSet<String>();
    private final TreeMap<Integer, String> argsNamesByIndex = new TreeMap<Integer, String>();
    private final Set<Parameter> bound = new HashSet<Parameter>();
    
    public NamedArgumentInvocationBuilder(
            AbstractTransformer gen, Tree.Primary primary,
            Declaration primaryDeclaration,
            Tree.InvocationExpression invocation) {
        super(gen, primary, primaryDeclaration, invocation.getTypeModel(), invocation);
        namedArgumentList = invocation.getNamedArgumentList();
        varBaseName = gen.aliasName("arg");
        callVarName = varBaseName + "$callable$";
    }
    
    @Override
    protected void compute() {
        if (primaryDeclaration == null) {
            return;
        }
        java.util.List<Tree.NamedArgument> namedArguments = namedArgumentList.getNamedArguments();
        java.util.List<ParameterList> paramLists = ((Functional)primaryDeclaration).getParameterLists();
        java.util.List<Parameter> declaredParams = paramLists.get(0).getParameters();
        appendVarsForNamedArguments(namedArguments, declaredParams);
        boolean hasDefaulted = appendVarsForDefaulted(declaredParams);
        for (String argName : this.argsNamesByIndex.values()) {
            appendArgument(gen.makeUnquotedIdent(argName));
        }
        if (hasDefaulted 
                && !Strategy.defaultParameterMethodStatic(primaryDeclaration)) {
            vars.prepend(makeThis());
        }
    }
    
    private JCExpression makeDefaultedArgumentMethodCall(Parameter param) {
        final String methodName = Util.getDefaultedParamMethodName(primaryDeclaration, param);
        JCExpression defaultValueMethodName;
        if (Strategy.defaultParameterMethodOnSelf(param)) {
            Declaration container = param.getDeclaration().getRefinedDeclaration();
            if (!container.isToplevel()) {
                container = (Declaration)container.getContainer();
            }
            String className = gen.getCompanionClassName(container);
            defaultValueMethodName = gen.makeQuotedQualIdent(gen.makeQuotedFQIdent(container.getQualifiedNameString()), className, methodName);
        } else if (Strategy.defaultParameterMethodStatic(param)) {
            Declaration container = param.getDeclaration().getRefinedDeclaration();
            if (!container.isToplevel()) {
                container = (Declaration)container.getContainer();
            }            
            defaultValueMethodName = gen.makeQuotedQualIdent(gen.makeQuotedFQIdent(container.getQualifiedNameString()), methodName);
        } else {
            defaultValueMethodName = gen.makeQuotedQualIdent(gen.makeQuotedIdent(varBaseName + "$this$"), methodName);
        }
        JCExpression argExpr = gen.at(node).Apply(null, 
                defaultValueMethodName, 
                makeVarRefArgumentList(param));
        return argExpr;
    }
    
    // Make a list of ($arg0, $arg1, ... , $argN)
    // or ($arg$this$, $arg0, $arg1, ... , $argN)
    private List<JCExpression> makeVarRefArgumentList(Parameter param) {
        ListBuffer<JCExpression> names = ListBuffer.<JCExpression> lb();
        if (!Strategy.defaultParameterMethodStatic(primaryDeclaration)
                && Strategy.defaultParameterMethodTakesThis(param)) {
            names.append(gen.makeUnquotedIdent(varBaseName + "$this$"));
        }
        final int parameterIndex = parameterIndex(param);
        for (int ii = 0; ii < parameterIndex; ii++) {
            names.append(gen.makeUnquotedIdent(this.argsNamesByIndex.get(ii)));
        }
        return names.toList();
    }
    
    /** Generates the argument name; namedArg may be null if no  
     * argument was given explicitly */
    private String argName(Parameter param) {
        final int paramIndex = parameterIndex(param);
        //if (this.argNames.isEmpty()) {
            //this.argNames.addAll(Collections.<String>nCopies(parameterList(param).size(), null));
        //}
        final String argName = varBaseName + "$" + paramIndex;
        if (this.argsNamesByIndex.containsValue(argName)) {
            throw new RuntimeException();
        }
        //if (!this.argNames.add(argName)) {
        //    throw new RuntimeException();
        //}
        return argName;
    }

    private java.util.List<Parameter> parameterList(Parameter param) {
        Functional functional = (Functional)param.getContainer();
        return functional.getParameterLists().get(0).getParameters();
    }
    
    private int parameterIndex(Parameter param) {
        return parameterList(param).indexOf(param);
    }
    
    private void appendVarsForNamedArguments(
            java.util.List<Tree.NamedArgument> namedArguments,
            java.util.List<Parameter> declaredParams) {
        boolean isRaw = primaryTypeArguments.isEmpty();
        // Assign vars for each named argument given
        for (Tree.NamedArgument namedArg : namedArguments) {
            gen.at(namedArg);
            Parameter declaredParam = namedArg.getParameter();
            BoxingStrategy boxType;
            ProducedType type = null;    
            if (declaredParam != null) {
                boxType = Util.getBoxingStrategy(declaredParam);
                type = gen.getTypeForParameter(declaredParam, isRaw, getTypeArguments());
            } else {
                // Arguments of overloaded methods don't have a reference to parameter
                boxType = BoxingStrategy.UNBOXED;
            }
            String argName = argName(declaredParam);
            ListBuffer<JCStatement> statements;
            if (namedArg instanceof Tree.SpecifiedArgument) {             
                Tree.SpecifiedArgument specifiedArg = (Tree.SpecifiedArgument)namedArg;
                Tree.Expression expr = specifiedArg.getSpecifierExpression().getExpression();
                if (declaredParam == null) {
                    type = expr.getTypeModel();
                }
                // if we can't pick up on the type from the declaration, revert to the type of the expression
                if(gen.isTypeParameter(gen.simplifyType(type)))
                    type = expr.getTypeModel();
                JCExpression typeExpr = gen.makeJavaType(type, (boxType == BoxingStrategy.BOXED) ? TYPE_ARGUMENT : 0);
                JCExpression argExpr = gen.expressionGen().transformExpression(expr, boxType, type);
                JCVariableDecl varDecl = gen.makeVar(argName, typeExpr, argExpr);
                statements = ListBuffer.<JCStatement>of(varDecl);
            } else if (namedArg instanceof Tree.MethodArgument) {
                Tree.MethodArgument methodArg = (Tree.MethodArgument)namedArg;
                // TODO MPL
                Method model = methodArg.getDeclarationModel();
                ProducedType callableType = gen.typeFact().getCallableType(model.getType());
                List<JCStatement> body = gen.statementGen().transform(methodArg.getBlock()).getStatements();
                if (gen.isVoid(model.getType())) {
                    body = body.append(gen.make().Return(gen.makeNull()));
                }
                CallableBuilder callableBuilder = CallableBuilder.methodArgument(gen.gen(), 
                        callableType, 
                        model.getParameterLists().get(0), 
                        body);
                JCNewClass callable = callableBuilder.build();
                JCExpression typeExpr = gen.makeJavaType(callableType);
                JCVariableDecl varDecl = gen.makeVar(argName, typeExpr, callable);
                statements = ListBuffer.<JCStatement>of(varDecl);
            } else if (namedArg instanceof Tree.ObjectArgument) {
                Tree.ObjectArgument objectArg = (Tree.ObjectArgument)namedArg;
                List<JCTree> object = gen.classGen().transformObjectArgument(objectArg);
                // No need to worry about boxing (it cannot be a boxed type) 
                JCVariableDecl varDecl = gen.makeLocalIdentityInstance(argName, objectArg.getIdentifier().getText(), false);
                statements = toStmts(objectArg, object).append(varDecl);
            } else if (namedArg instanceof Tree.AttributeArgument) {
                Tree.AttributeArgument attrArg = (Tree.AttributeArgument)namedArg;
                final Getter model = attrArg.getDeclarationModel();
                final String name = model.getName();
                final String alias = gen.aliasName(name);
                final List<JCTree> attrClass = gen.gen().transformAttribute(model, alias, alias, attrArg.getBlock(), null, null);
                // TODO Type params
                JCExpression initValue = gen.make().Apply(null, 
                        gen.makeSelect(alias, Util.getGetterName(name)),
                        List.<JCExpression>nil());
                // TODO Boxing
                initValue = gen.expressionGen().boxUnboxIfNecessary(initValue, 
                        true, 
                        model.getType(), 
                        BoxingStrategy.UNBOXED);
                JCTree.JCVariableDecl var = gen.make().VarDef(
                        gen.make().Modifiers(FINAL, List.<JCAnnotation>nil()), 
                        gen.names().fromString(argName), 
                        gen.makeJavaType(model.getType()), 
                        initValue);
                statements = toStmts(attrArg, attrClass).append(var);
            } else {
                throw new RuntimeException("" + namedArg);
            }
            bind(declaredParam, argName, statements.toList());
        }
    }
    
    private void bind(Parameter param, String argName, List<JCStatement> statements) {
        this.vars.appendList(statements);
        this.argsNamesByIndex.put(parameterIndex(param), argName);
        this.bound.add(param);
    }
    
    private ListBuffer<JCStatement> toStmts(Tree.NamedArgument namedArg, final List<JCTree> listOfStatements) {
        final ListBuffer<JCStatement> result = ListBuffer.<JCStatement>lb();
        for (JCTree tree : listOfStatements) {
            if (tree instanceof JCStatement) {
                result.append((JCStatement)tree);
            } else {
                result.append(gen.make().Exec(gen.makeErroneous(namedArg, "Attempt to put a non-statement in a Let")));
            }
        }
        return result;
    }
    
    private final void appendDefaulted(Parameter param, boolean isRaw, JCExpression argExpr) {
        int flags = 0;
        if (isRaw) {
            flags |= WANT_RAW_TYPE;
        } else if (Util.getBoxingStrategy(param) == BoxingStrategy.BOXED) {
            flags |= TYPE_ARGUMENT;
        }
        ProducedType type = gen.getTypeForParameter(param, isRaw, getTypeArguments());
        String argName = argName(param);
        JCExpression typeExpr = gen.makeJavaType(type, flags);
        JCVariableDecl varDecl = gen.makeVar(argName, typeExpr, argExpr);
        bind(param, argName, List.<JCStatement>of(varDecl));
    }
    
    private boolean appendVarsForDefaulted(java.util.List<Parameter> declaredParams) {
        boolean hasDefaulted = false;
        boolean isRaw = primaryTypeArguments.isEmpty();
        if (!Decl.isOverloaded(primaryDeclaration)) {
            // append any arguments for defaulted parameters
            for (Parameter param : declaredParams) {
                if (bound.contains(param)) {
                    continue;
                }
                final JCExpression argExpr;
                if (param.isDefaulted()) {
                    argExpr = makeDefaultedArgumentMethodCall(param);
                    hasDefaulted |= true;
                } else if (param.isSequenced()) {
                    Tree.SequencedArgument sequencedArgument = namedArgumentList.getSequencedArgument();
                    if (sequencedArgument != null) {
                        gen.at(sequencedArgument);
                        argExpr = gen.makeSequenceRaw(sequencedArgument.getExpressionList().getExpressions());
                        // TODO I suspect the above is wrong and we should use
                        // argExpr = makeDefaultedArgumentMethodCall(param);
                        //hasDefaulted |= true;
                    } else {
                        argExpr = gen.makeEmpty();
                    }
                } else {
                    argExpr = gen.makeErroneous(this.node, "Missing argument, and parameter is not defaulted");
                }
                appendDefaulted(param, isRaw, argExpr);
            }
        }
        return hasDefaulted;
    }
    
    private final JCVariableDecl makeThis() {
        // first append $this
        JCExpression defaultedParameterInstance;
        // TODO Fix how we figure out the thisType, because it's doesn't 
        // handle type parameters correctly
        // we used to use thisType = gen.getThisType(getPrimaryDeclaration());
        final JCExpression thisType;
        ProducedReference target = ((Tree.MemberOrTypeExpression)primary).getTarget();
        if (primary instanceof Tree.BaseMemberExpression) {
            if (Decl.withinClassOrInterface(primaryDeclaration)) {
                // a member method
                thisType = gen.makeJavaType(target.getQualifyingType(), NO_PRIMITIVES);
                defaultedParameterInstance = gen.makeUnquotedIdent("this");
            } else {
                // a local or toplevel function
                defaultedParameterInstance = gen.makeUnquotedIdent(primaryDeclaration.getName());
                thisType = gen.makeQuotedIdent(primaryDeclaration.getName());
            }
        } else if (primary instanceof Tree.BaseTypeExpression
                || primary instanceof Tree.QualifiedTypeExpression) {
            Map<TypeParameter, ProducedType> typeA = target.getTypeArguments();
            ListBuffer<JCExpression> typeArgs = ListBuffer.<JCExpression>lb();
            for (TypeParameter tp : ((TypeDeclaration)target.getDeclaration()).getTypeParameters()) {
                ProducedType producedType = typeA.get(tp);
                typeArgs.append(gen.makeJavaType(producedType, TYPE_ARGUMENT));
            }
            ClassOrInterface declaration = (ClassOrInterface)((Tree.BaseTypeExpression) primary).getDeclaration();
            thisType = gen.makeCompanionType(declaration, typeArgs.toList());
            defaultedParameterInstance = gen.make().NewClass(
                    null, 
                    null,
                    gen.makeCompanionType(declaration, typeArgs.toList()), 
                    List.<JCExpression>nil(), null);
        } else {
            thisType = gen.makeJavaType(target.getQualifyingType(), NO_PRIMITIVES);
            defaultedParameterInstance = gen.makeUnquotedIdent(callVarName);
        }
        JCVariableDecl thisDecl = gen.makeVar(varBaseName + "$this$", 
                thisType, 
                defaultedParameterInstance);
        return thisDecl;
    }
    
    @Override
    protected JCExpression transformInvocation(JCExpression primaryExpr, String selector) {
        JCExpression resultExpr = super.transformInvocation(primaryExpr, selector);
        // apply the default parameters
        if (vars != null && !vars.isEmpty()) {
            if (returnType == null || gen.isVoid(returnType)) {
                // void methods get wrapped like (let $arg$1=expr, $arg$0=expr in call($arg$0, $arg$1); null)
                resultExpr = gen.make().LetExpr( 
                        vars.append(gen.make().Exec(resultExpr)).toList(), 
                        gen.makeNull());
            } else {
                // all other methods like (let $arg$1=expr, $arg$0=expr in call($arg$0, $arg$1))
                resultExpr = gen.make().LetExpr( 
                        vars.toList(),
                        resultExpr);
            }
        }
        return resultExpr;
    }
    
    @Override
    protected JCExpression transformInvocationPrimary(JCExpression primaryExpr,
            String selector) {
        JCExpression actualPrimExpr = super.transformInvocationPrimary(primaryExpr, selector);
        if (vars != null 
                && !vars.isEmpty() 
                && primaryExpr != null
                && selector != null) {
            // Prepare the first argument holding the primary for the call
            ProducedType type = ((Tree.MemberOrTypeExpression)primary).getTarget().getQualifyingType();
            vars.prepend(gen.makeVar(callVarName, gen.makeJavaType(type, NO_PRIMITIVES), actualPrimExpr));
            actualPrimExpr = gen.makeUnquotedIdent(callVarName);
        }
        return actualPrimExpr;
    }
}