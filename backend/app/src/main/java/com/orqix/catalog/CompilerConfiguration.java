package com.orqix.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.orqix.compiler.Compilers;
import com.orqix.compiler.DecisionEvaluator;
import com.orqix.compiler.FormValidator;
import com.orqix.compiler.WorkflowCompiler;

/** The pure compiler module's default implementations, registered as beans (S4). */
@Configuration(proxyBeanMethods = false)
public class CompilerConfiguration {

    @Bean
    WorkflowCompiler workflowCompiler() {
        return Compilers.workflowCompiler();
    }

    @Bean
    FormValidator formValidator() {
        return Compilers.formValidator();
    }

    @Bean
    DecisionEvaluator decisionEvaluator() {
        return Compilers.decisionEvaluator();
    }
}
