/*
 * Copyright 2010-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve.java;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.analyzer.AnalyzeExhaust;
import org.jetbrains.jet.context.ContextPackage;
import org.jetbrains.jet.context.GlobalContext;
import org.jetbrains.jet.context.GlobalContextImpl;
import org.jetbrains.jet.di.InjectorForLazyResolveWithJava;
import org.jetbrains.jet.di.InjectorForTopDownAnalyzerForJvm;
import org.jetbrains.jet.lang.descriptors.PackageFragmentProvider;
import org.jetbrains.jet.lang.descriptors.impl.CompositePackageFragmentProvider;
import org.jetbrains.jet.lang.descriptors.impl.ModuleDescriptorImpl;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.BindingTrace;
import org.jetbrains.jet.lang.resolve.ImportPath;
import org.jetbrains.jet.lang.resolve.TopDownAnalysisParameters;
import org.jetbrains.jet.lang.resolve.java.mapping.JavaToKotlinClassMap;
import org.jetbrains.jet.lang.resolve.kotlin.incremental.IncrementalCache;
import org.jetbrains.jet.lang.resolve.kotlin.incremental.IncrementalPackageFragmentProvider;
import org.jetbrains.jet.lang.resolve.lazy.ResolveSession;
import org.jetbrains.jet.lang.resolve.lazy.declarations.DeclarationProviderFactory;
import org.jetbrains.jet.lang.resolve.lazy.declarations.DeclarationProviderFactoryService;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public enum AnalyzerFacadeForJVM  {

    INSTANCE {
    };

    public static final List<ImportPath> DEFAULT_IMPORTS = ImmutableList.of(
            new ImportPath("java.lang.*"),
            new ImportPath("kotlin.*"),
            new ImportPath("kotlin.jvm.*"),
            new ImportPath("kotlin.io.*")
    );

    private AnalyzerFacadeForJVM() {
    }

    @NotNull
    public static ResolveSession createResolveSessionForFiles(
            @NotNull Project project,
            @NotNull Collection<JetFile> syntheticFiles,
            @NotNull GlobalSearchScope filesScope,
            @NotNull BindingTrace trace,
            boolean addBuiltIns
    ) {
        GlobalContextImpl globalContext = ContextPackage.GlobalContext();

        DeclarationProviderFactory declarationProviderFactory = DeclarationProviderFactoryService.OBJECT$
                .createDeclarationProviderFactory(project, globalContext.getStorageManager(), syntheticFiles, filesScope);

        InjectorForLazyResolveWithJava resolveWithJava = new InjectorForLazyResolveWithJava(
                project,
                globalContext,
                declarationProviderFactory,
                trace);

        ModuleDescriptorImpl module = resolveWithJava.getModule();
        module.initialize(
                new CompositePackageFragmentProvider(
                        Arrays.asList(
                                resolveWithJava.getResolveSession().getPackageFragmentProvider(),
                                resolveWithJava.getJavaDescriptorResolver().getPackageFragmentProvider()
                        )));

        module.addDependencyOnModule(module);
        if (addBuiltIns) {
            module.addDependencyOnModule(KotlinBuiltIns.getInstance().getBuiltInsModule());
        }
        module.seal();
        return resolveWithJava.getResolveSession();
    }

    @NotNull
    public static AnalyzeExhaust analyzeFilesWithJavaIntegration(
            Project project,
            Collection<JetFile> files,
            BindingTrace trace,
            Predicate<PsiFile> filesToAnalyzeCompletely,
            ModuleDescriptorImpl module,
            List<String> moduleIds,
            IncrementalCache incrementalCache
    ) {
        GlobalContext globalContext = ContextPackage.GlobalContext();
        TopDownAnalysisParameters topDownAnalysisParameters = TopDownAnalysisParameters.create(
                globalContext.getStorageManager(),
                globalContext.getExceptionTracker(),
                filesToAnalyzeCompletely,
                false,
                false
        );

        InjectorForTopDownAnalyzerForJvm injector = new InjectorForTopDownAnalyzerForJvm(project, topDownAnalysisParameters, trace, module);
        try {
            List<PackageFragmentProvider> additionalProviders = new ArrayList<PackageFragmentProvider>();

            if (incrementalCache != null && moduleIds != null) {
                for (String moduleId : moduleIds) {
                    additionalProviders.add(
                            new IncrementalPackageFragmentProvider(
                                    files, module, globalContext.getStorageManager(), injector.getDeserializationGlobalContextForJava(),
                                    incrementalCache, moduleId, injector.getJavaDescriptorResolver()
                            )
                    );
                }
            }
            additionalProviders.add(injector.getJavaDescriptorResolver().getPackageFragmentProvider());

            injector.getTopDownAnalyzer().analyzeFiles(topDownAnalysisParameters, files, additionalProviders);
            return AnalyzeExhaust.success(trace.getBindingContext(), module);
        }
        finally {
            injector.destroy();
        }
    }

    @NotNull
    public static ModuleDescriptorImpl createJavaModule(@NotNull String name) {
        return new ModuleDescriptorImpl(Name.special(name), DEFAULT_IMPORTS, JavaToKotlinClassMap.getInstance());
    }
}
