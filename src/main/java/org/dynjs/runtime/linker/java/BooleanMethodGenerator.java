package org.dynjs.runtime.linker.java;

import static me.qmx.jitescript.util.CodegenUtils.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import me.qmx.jitescript.CodeBlock;
import me.qmx.jitescript.JiteClass;

import org.dynjs.codegen.CodeGeneratingVisitor.Arities;
import org.dynjs.runtime.ExecutionContext;
import org.dynjs.runtime.JSFunction;
import org.dynjs.runtime.JSObject;
import org.dynjs.runtime.Types;
import org.dynjs.runtime.Types.Undefined;
import org.objectweb.asm.tree.LabelNode;

public class BooleanMethodGenerator extends MethodGenerator {
    
    public void defineMethod(final Method method, final JiteClass jiteClass, final Class<?> superClass) {

        if (method.getName().equals("equals") || method.getName().equals("hashCode") || method.getName().equals("toString")) {
            return;
        }

        final Class<?>[] params = method.getParameterTypes();
        final Class<?>[] signature = new Class<?>[params.length + 1];

        for (int i = 1; i < params.length + 1; ++i) {
            signature[i] = params[i - 1];
        }

        signature[0] = method.getReturnType();

        jiteClass.defineMethod(method.getName(), method.getModifiers() & ~Modifier.ABSTRACT, sig(signature), new CodeBlock() {
            {
                LabelNode noImpl = new LabelNode();
                LabelNode complete = new LabelNode();

                callJavascriptImplementation(method, jiteClass, this, noImpl);
                // result
                coerceTo(Boolean.class, jiteClass, this);
                // Boolean
                invokevirtual(p(Boolean.class), "booleanValue", sig(boolean.class));
                // boolean
                go_to(complete);

                label(noImpl);
                
                callSuperImplementation(method, superClass, this);
                // result

                label(complete);
                // result
                ireturn();
            }
        });

    }

    @Override
    protected void handleDefaultReturnValue(CodeBlock block) {
        block.iconst_0();
    }

}
