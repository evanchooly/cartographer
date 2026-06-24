package com.antwerkz.cartographer.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class CartographerTransformer {

    public static void install(AgentConfig config, Instrumentation instrumentation) {
        ElementMatcher.Junction<TypeDescription> packageMatcher = null;
        for (String pkg : config.getPackages()) {
            ElementMatcher.Junction<TypeDescription> m = ElementMatchers.nameStartsWith(pkg);
            packageMatcher = packageMatcher == null ? m : packageMatcher.or(m);
        }

        final ElementMatcher.Junction<MethodDescription> testAnnotations =
            ElementMatchers.<MethodDescription>isAnnotatedWith(ElementMatchers.named("org.junit.jupiter.api.Test"))
                .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.junit.jupiter.params.ParameterizedTest")))
                .or(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.testng.annotations.Test")));

        final ElementMatcher.Junction<MethodDescription> generalMethods =
            ElementMatchers.<MethodDescription>isMethod()
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(testAnnotations));

        final ElementMatcher.Junction<MethodDescription> ctors =
            ElementMatchers.<MethodDescription>isConstructor()
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()));

        new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .ignore(ElementMatchers.<TypeDescription>nameStartsWith("net.bytebuddy.")
                .or(ElementMatchers.nameStartsWith("com.antwerkz.cartographer.shaded."))
                .or(ElementMatchers.nameStartsWith("io.opentelemetry."))
                .or(ElementMatchers.nameStartsWith("sun."))
                .or(ElementMatchers.nameStartsWith("jdk.")))
            .type(packageMatcher)
            .transform(new AgentBuilder.Transformer() {
                @Override
                public DynamicType.Builder<?> transform(
                        DynamicType.Builder<?> builder,
                        TypeDescription typeDescription,
                        ClassLoader classLoader,
                        JavaModule module,
                        ProtectionDomain protectionDomain) {
                    return builder
                        .visit(Advice.to(TestRootAdvice.class).on(testAnnotations))
                        .visit(Advice.to(MethodAdvice.class).on(generalMethods))
                        .visit(Advice.to(CtorAdvice.class).on(ctors));
                }
            })
            .installOn(instrumentation);
    }
}
