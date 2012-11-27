package org.dynjs.codegen;

import static me.qmx.jitescript.util.CodegenUtils.*;

import java.util.List;

import me.qmx.jitescript.CodeBlock;

import org.dynjs.codegen.AbstractCodeGeneratingVisitor.Arities;
import org.dynjs.exception.ThrowException;
import org.dynjs.parser.ast.AssignmentExpression;
import org.dynjs.parser.ast.Expression;
import org.dynjs.parser.ast.FunctionCallExpression;
import org.dynjs.parser.ast.NewOperatorExpression;
import org.dynjs.parser.ast.VariableDeclaration;
import org.dynjs.runtime.BlockManager;
import org.dynjs.runtime.EnvironmentRecord;
import org.dynjs.runtime.ExecutionContext;
import org.dynjs.runtime.GlobalObject;
import org.dynjs.runtime.JSObject;
import org.dynjs.runtime.Reference;
import org.dynjs.runtime.linker.DynJSBootstrapper;
import org.objectweb.asm.tree.LabelNode;

public class InvokeDynamicBytecodeGeneratingVisitor extends BasicBytecodeGeneratingVisitor {

    public InvokeDynamicBytecodeGeneratingVisitor(BlockManager blockManager) {
        super(blockManager);
    }

    @Override
    public void visit(ExecutionContext context, VariableDeclaration expr, boolean strict) {
        if (expr.getExpr() == null) {
            ldc(expr.getIdentifier());
            // str
        } else {
            append(jsResolve(expr.getIdentifier()));
            // reference
            aload(Arities.EXECUTION_CONTEXT);
            // reference context
            ldc( expr.getIdentifier() );
            // reference context name
            expr.getExpr().accept(context, this, strict);
            // reference context name val
            append(jsGetValue());
            // reference context name val
            invokedynamic("fusion:setProperty", sig(void.class, Reference.class, ExecutionContext.class, String.class, Object.class), DynJSBootstrapper.HANDLE, DynJSBootstrapper.ARGS);
            // <empty>
            ldc(expr.getIdentifier());
            // str
        }
    }

    @Override
    public void visit(ExecutionContext context, AssignmentExpression expr, boolean strict) {
        LabelNode throwRefError = new LabelNode();
        LabelNode end = new LabelNode();

        expr.getLhs().accept(context, this, strict);
        // ref
        dup();
        // ref ref
        instance_of(p(Reference.class));
        // ref bool
        iffalse(throwRefError);
        // ref
        checkcast(p(Reference.class));
        // ref

        expr.getRhs().accept(context, this, strict);
        // ref expr
        append(jsGetValue());
        // ref value
        dup_x1();
        // value ref value
        swap();
        // value value ref
        dup_x1();
        // value ref value ref
        invokevirtual(p(Reference.class), "getReferencedName", sig(String.class));
        // value ref value name
        dup_x1();
        // value ref name value name
        pop();
        // value ref name value
        aload(Arities.EXECUTION_CONTEXT);
        // value ref name value context
        dup_x2();
        // value ref context name value context
        pop();
        // value ref context name value
        invokedynamic("fusion:setProperty", sig(void.class, Reference.class, ExecutionContext.class, String.class, Object.class), DynJSBootstrapper.HANDLE,
                DynJSBootstrapper.ARGS);
        // value
        go_to(end);

        label(throwRefError);
        // reference
        pop();

        newobj(p(ThrowException.class));
        // ex
        dup();
        // ex ex
        aload(Arities.EXECUTION_CONTEXT);
        // ex ex context
        ldc(expr.getLhs().toString() + " is not a reference");
        // ex ex context str
        invokevirtual(p(ExecutionContext.class), "createReferenceError", sig(JSObject.class, String.class));
        // ex ex error
        aload(Arities.EXECUTION_CONTEXT);
        // ex ex error context
        swap();
        // ex ex context error
        invokespecial(p(ThrowException.class), "<init>", sig(void.class, ExecutionContext.class, Object.class));
        // ex ex
        athrow();

        label(end);
        nop();

    }

    @Override
    public void visit(ExecutionContext context, NewOperatorExpression expr, boolean strict) {
        LabelNode end = new LabelNode();
        // 11.2.2

        aload(Arities.EXECUTION_CONTEXT);
        // context
        invokevirtual(p(ExecutionContext.class), "incrementPendingConstructorCount", sig(void.class));
        // <empty>

        expr.getExpr().accept(context, this, strict);
        // obj

        aload(Arities.EXECUTION_CONTEXT);
        // obj context
        invokevirtual(p(ExecutionContext.class), "getPendingConstructorCount", sig(int.class));
        // obj count
        iffalse(end);

        // obj
        aload(Arities.EXECUTION_CONTEXT);
        // obj context
        swap();
        // context obj
        append(jsGetValue());
        // context ctor-fn
        swap();
        // ctor-fn context

        bipush(0);
        anewarray(p(Object.class));
        // ctor-fn context array

        invokedynamic("fusion:construct", sig(Object.class, Object.class, ExecutionContext.class, Object[].class), DynJSBootstrapper.HANDLE, DynJSBootstrapper.ARGS);
        // obj

        label(end);
        nop();
    }

    @Override
    public void visit(ExecutionContext context, FunctionCallExpression expr, boolean strict) {
        LabelNode propertyRef = new LabelNode();
        LabelNode noSelf = new LabelNode();
        LabelNode doCall = new LabelNode();
        LabelNode isCallable = new LabelNode();
        // 11.2.3

        expr.getMemberExpression().accept(context, this, strict);
        // fnexpr
        /*
        dup();
        // fnexpr fnexpr
        append(jsGetValue());
        // fnexpr function
        swap();
        // function fnexpr
         */
        
        dup();
        // fnexpr fnexpr
        instance_of(p(Reference.class));
        // fnexpr isref?
        iffalse(noSelf);

        // ----------------------------------------
        // Reference

        // ref
        checkcast(p(Reference.class));
        // ref
        dup();
        // ref ref
        invokevirtual(p(Reference.class), "isPropertyReference", sig(boolean.class));
        // ref bool(is-prop)

        iftrue(propertyRef);

        // ----------------------------------------
        // Environment Record

        // ref
        dup();
        // ref ref
        append(jsGetBase());
        // ref base
        checkcast(p(EnvironmentRecord.class));
        // ref env-rec
        invokeinterface(p(EnvironmentRecord.class), "implicitThisValue", sig(Object.class));
        // ref self
        go_to(doCall);

        // ----------------------------------------
        // Property Reference
        label(propertyRef);
        // ref
        dup();
        // ref ref
        append(jsGetBase());
        // ref self
        go_to(doCall);

        // ------------------------------------------
        // No self
        label(noSelf);
        // function 
        append(jsPushUndefined());
        // function UNDEFINED

        // ------------------------------------------
        // call()

        label(doCall);
        // ref self

        aload(Arities.EXECUTION_CONTEXT);
        // ref self context
        
        dup_x1();
        // ref context self context

        invokevirtual(p(ExecutionContext.class), "pushCallContext", sig(void.class));
        // ref context self

        List<Expression> argExprs = expr.getArgumentExpressions();
        int numArgs = argExprs.size();
        bipush(numArgs);
        anewarray(p(Object.class));
        // ref context self array
        for (int i = 0; i < numArgs; ++i) {
            dup();
            bipush(i);

            argExprs.get(i).accept(context, this, strict);
            append(jsGetValue());
            aastore();
        }
        // ref context self array

        aload(Arities.EXECUTION_CONTEXT);
        // ref context self array context
        invokevirtual(p(ExecutionContext.class), "popCallContext", sig(void.class));
        // ref context self array

        // function context ref self args
        invokedynamic("fusion:call", sig(Object.class, Object.class, ExecutionContext.class, Object.class, Object[].class), DynJSBootstrapper.HANDLE, DynJSBootstrapper.ARGS);
        // value
    }

    /*
     * @Override
     * public void visit(ExecutionContext context, AssignmentExpression expr, boolean strict) {
     * LabelNode throwRefError = new LabelNode();
     * LabelNode end = new LabelNode();
     * 
     * LabelNode isUnresolvableRef = new LabelNode();
     * LabelNode isPropertyRef = new LabelNode();
     * LabelNode isEnvRecord = new LabelNode();
     * LabelNode doPut = new LabelNode();
     * 
     * expr.getLhs().accept(context, this, strict);
     * // reference
     * dup();
     * // reference reference
     * instance_of(p(Reference.class));
     * // reference bool
     * iffalse(throwRefError);
     * // reference
     * checkcast(p(Reference.class));
     * // ref
     * dup();
     * // ref ref
     * invokevirtual(p(Reference.class), "isUnresolvableReference", sig(boolean.class));
     * // ref unresolv?
     * iftrue(isUnresolvableRef);
     * // ref
     * dup();
     * // ref ref
     * invokevirtual(p(Reference.class), "isPropertyReference", sig(boolean.class));
     * // ref isprop?
     * iftrue(isPropertyRef);
     * // ref
     * go_to(isEnvRecord);
     * 
     * // ----------------------------------------
     * // unresolvable ref
     * // ----------------------------------------
     * 
     * label(isUnresolvableRef);
     * // ref
     * dup();
     * // ref ref
     * invokevirtual(p(Reference.class), "isStrictReference", sig(boolean.class));
     * // ref isstrict?
     * iftrue(throwRefError);
     * // ref
     * aload(Arities.EXECUTION_CONTEXT);
     * // ref context
     * invokevirtual(p(ExecutionContext.class), "getGlobalObject", sig(GlobalObject.class));
     * // ref obj
     * go_to(doPut);
     * 
     * // ----------------------------------------
     * // property ref
     * // ----------------------------------------
     * 
     * label( isPropertyRef );
     * // ref
     * dup();
     * // ref ref
     * invokevirtual(p(Reference.class), "getBase", sig(Object.class));
     * // ref obj
     * go_to( doPut );
     * 
     * // ----------------------------------------
     * // property ref
     * // ----------------------------------------
     * 
     * label( isEnvRecord );
     * // ref
     * dup();
     * // ref ref
     * invokevirtual(p(Reference.class), "getBase", sig(Object.class));
     * // ref obj
     * go_to( doPut );
     * 
     * 
     * label( doPut );
     * // ref obj
     * swap();
     * // obj ref
     * dup();
     * // obj ref ref
     * aload(Arities.EXECUTION_CONTEXT);
     * // obj ref ref context
     * invokestatic(p(ReferenceContext.class), "create", sig(ReferenceContext.class, Reference.class, ExecutionContext.class));
     * // obj ref context
     * swap();
     * // obj context ref
     * invokevirtual(p(Reference.class), "getReferencedName", sig(String.class));
     * // obj context name
     * expr.getRhs().accept(context, this, strict);
     * // obj context name value
     * append(jsGetValue());
     * // object context name value
     * invokedynamic("dyn:setProp", sig(Object.class, Object.class, ReferenceContext.class, String.class, Object.class), DynJSBootstrapper.HANDLE, DynJSBootstrapper.ARGS);
     * // value
     * go_to(end);
     * 
     * label(throwRefError);
     * // reference
     * pop();
     * 
     * newobj(p(ThrowException.class));
     * // ex
     * dup();
     * // ex ex
     * aload(Arities.EXECUTION_CONTEXT);
     * // ex ex context
     * ldc(expr.getLhs().toString() + " is not a reference");
     * // ex ex context str
     * invokevirtual(p(ExecutionContext.class), "createReferenceError", sig(JSObject.class, String.class));
     * // ex ex error
     * aload(Arities.EXECUTION_CONTEXT);
     * // ex ex error context
     * swap();
     * // ex ex context error
     * invokespecial(p(ThrowException.class), "<init>", sig(void.class, ExecutionContext.class, Object.class));
     * // ex ex
     * athrow();
     * 
     * label(end);
     * nop();
     * 
     * }
     */

    @Override
    public CodeBlock jsGetValue(final Class<?> throwIfNot) {
        return new CodeBlock() {
            {
                // IN: reference
                LabelNode end = new LabelNode();
                LabelNode throwRef = new LabelNode();

                dup();
                // ref ref
                instance_of(p(Reference.class));
                // ref isref?
                iffalse(end);
                checkcast(p(Reference.class));
                // ref
                dup();
                // ref ref
                invokevirtual(p(Reference.class), "isUnresolvableReference", sig(boolean.class));
                // ref unresolv?
                iftrue(throwRef);
                // ref
                dup();
                // ref ref
                invokevirtual(p(Reference.class), "getReferencedName", sig(String.class));
                // ref name
                aload(Arities.EXECUTION_CONTEXT);
                // ref name context
                swap();
                // ref context name
                invokedynamic("fusion:getProperty|getMethod", sig(Object.class, Reference.class, ExecutionContext.class, String.class), DynJSBootstrapper.HANDLE,
                        DynJSBootstrapper.ARGS);
                // value
                if (throwIfNot != null) {
                    dup();
                    // value value
                    instance_of(p(throwIfNot));
                    // value bool
                    iftrue(end);
                    // value
                    pop();
                    append(jsThrowTypeError("expected " + throwIfNot.getName()));
                }
                // result
                go_to(end);

                label(throwRef);
                append(jsThrowReferenceError("unable to dereference"));

                label(end);
                // value
                nop();
            }
        };
    }

}
