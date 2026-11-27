package net.kdt.pojavlaunch.ultraoptimization;

import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Candidate;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.DataSource;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.DependencyInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Environment;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.FileInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ItemState;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Loader;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.PlanItem;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ProgressCallback;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ProjectInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Resolution;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.VersionInfo;

/**
 * On-demand, cost-aware compatibility resolver.
 *
 * No mod version is hardcoded. Candidate projects are queried through a small DataSource and
 * every candidate/dependency version must declare the exact Minecraft version, a suitable
 * loader, a stable release channel, a primary JAR and at least one usable hash.
 */
public final class UltraOptimizationResolver {
    private UltraOptimizationResolver() {}

    private static final long THREE_YEARS_MS = 3L * 365L * 24L * 60L * 60L * 1000L;
    private static final long EIGHT_YEARS_MS = 8L * 365L * 24L * 60L * 60L * 1000L;
    private static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_DEPENDENCY_DEPTH = 3;
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9_.+\\-]{1,160}");

    private static final Set<String> ACCEPTED_PROJECT_STATUS = new HashSet<>(Arrays.asList(
            "approved", "listed"));
    private static final Set<String> ACCEPTED_VERSION_STATUS = new HashSet<>(Arrays.asList(
            "listed"));
    private static final Set<String> ACCEPTED_VERSION_TYPES = new HashSet<>(Arrays.asList(
            "release", "beta"));

    private static final class FileSelection {
        FileInfo file;
        String fileName;
        String url;
        String hashAlgorithm;
        String hash;
    }

    private static final class VersionChoice {
        VersionInfo version;
        String reason;
        VersionChoice(VersionInfo version, String reason) {
            this.version = version;
            this.reason = reason;
        }
    }

    private static final class CandidateAnalysis {
        Candidate candidate;
        ProjectInfo project;
        PlanItem parent;
        final List<PlanItem> dependencies = new ArrayList<>();
        final Set<String> involvedProjectIds = new HashSet<>();
        final Set<String> incompatibleProjectIds = new HashSet<>();
        String skipReason;
        int score;
    }

    public static Resolution resolve(Environment environment, DataSource source,
                                     ProgressCallback progress) {
        Resolution resolution = new Resolution();
        resolution.environment = environment;
        resolution.budgetCount = budgetCount(environment);
        boolean fatal = false;
        int totalSteps = UltraOptimizationModels.candidates().size() + 2;
        report(progress, "Reading selected installation", 0, totalSteps);

        if (environment == null || source == null) {
            addFatal(resolution, "Ultra-Optimization environment or data source is unavailable.");
            fatal = true;
        } else if (environment.gameDirectory == null || environment.gameDirectory.trim().isEmpty()) {
            addFatal(resolution, "Selected installation has no usable game directory.");
            fatal = true;
        } else if (environment.sharedData) {
            addFatal(resolution, "This installation uses a shared game directory. Use a dedicated installation so Ultra-Optimization cannot modify another installation.");
            fatal = true;
        } else if (environment.loader == Loader.VANILLA) {
            addFatal(resolution, "Vanilla detected. The existing installation is untouched. Create or select a Fabric, Forge, NeoForge or Quilt installation before optimizing.");
            fatal = true;
        } else if (environment.loader == Loader.UNKNOWN) {
            addFatal(resolution, "The selected installation's mod loader could not be identified.");
            fatal = true;
        } else if (UltraOptimizationDetector.isVersionAlias(environment.minecraftVersion)) {
            addFatal(resolution, "The exact Minecraft version is unavailable. Launch the installation once or select a concrete version before optimizing.");
            fatal = true;
        } else {
            int requiredJava = environment.requiredJavaMajor > 0
                    ? environment.requiredJavaMajor
                    : UltraOptimizationDetector.minimumJavaMajor(environment.minecraftVersion);
            resolution.requiredJavaMajor = requiredJava;
            if (environment.runtimeJavaMajor > 0 && environment.runtimeJavaMajor < requiredJava) {
                addFatal(resolution, "Selected Java " + environment.runtimeJavaMajor
                        + " is older than Java " + requiredJava + " required by Minecraft "
                        + environment.minecraftVersion + ". No mods were installed and no runtime was changed.");
                fatal = true;
            } else if (environment.runtimeJavaMajor <= 0) {
                resolution.warnings.add("The active Java runtime could not be read. The launcher will select one at launch; Ultra-Optimization does not change it.");
            }
        }

        UltraOptimizationDetector.RendererPolicy sodiumPolicy =
                UltraOptimizationDetector.sodiumPolicy(
                        environment == null ? null : environment.renderer,
                        environment == null ? null : environment.minecraftVersion);
        resolution.sodiumPolicyReason = sodiumPolicy.reason;
        resolution.warnings.add("Sodium policy: " + sodiumPolicy.reason);

        if (fatal) {
            resolution.canInstall = false;
            resolution.summary = "Ultra-Optimization made no changes. " + firstError(resolution);
            report(progress, "Compatibility check stopped", totalSteps, totalSteps);
            return resolution;
        }

        List<Candidate> candidates = UltraOptimizationModels.candidates();
        List<String> slugs = new ArrayList<>();
        for (Candidate candidate : candidates) slugs.add(candidate.slug);
        Map<String, ProjectInfo> projects = loadProjects(source, slugs, resolution.warnings);

        InstalledSnapshot installed = installedSnapshot(environment);
        List<CandidateAnalysis> installable = new ArrayList<>();
        int completed = 1;
        for (Candidate candidate : candidates) {
            report(progress, "Checking " + candidate.title, completed++, totalSteps);
            ProjectInfo project = projects.get(candidate.slug.toLowerCase(Locale.US));
            try {
                String installedReason = installedReason(candidate, project, installed);
                if (installedReason != null) {
                    PlanItem item = skippedOrInstalled(candidate, project, ItemState.INSTALLED,
                            installedReason, installed.matchingFileName(candidate));
                    resolution.items.add(item);
                    continue;
                }
                String skip = projectSkipReason(candidate, project, environment);
                if (skip != null) {
                    resolution.items.add(skippedOrInstalled(candidate, project, ItemState.SKIPPED, skip, null));
                    continue;
                }
                if (candidate.sodium && !sodiumPolicy.allowed) {
                    resolution.items.add(skippedOrInstalled(candidate, project, ItemState.SKIPPED,
                            sodiumPolicy.reason, null));
                    continue;
                }
                if (project == null) {
                    project = source.project(candidate.slug);
                }
                VersionChoice choice = chooseVersion(environment, source, candidate.slug,
                        project, candidate.slug);
                if (choice.version == null) {
                    resolution.items.add(skippedOrInstalled(candidate, project, ItemState.SKIPPED,
                            choice.reason, null));
                    continue;
                }
                FileSelection selection = selectFile(choice.version);
                CandidateAnalysis analysis = new CandidateAnalysis();
                analysis.candidate = candidate;
                analysis.project = project;
                analysis.parent = planItem(candidate, project, choice.version, selection,
                        "Compatible " + environment.loader.displayName() + " build for Minecraft "
                                + environment.minecraftVersion + ".");
                analysis.score = candidate.netScore();
                analysis.involvedProjectIds.add(lowerId(analysis.parent.projectId));
                analysis.involvedProjectIds.add(lowerId(candidate.slug));
                collectDependencyInfo(choice.version, analysis.incompatibleProjectIds);
                analysis.dependencies.addAll(resolveRequiredDependencies(environment, source,
                        analysis.parent, choice.version, new HashSet<String>(),
                        analysis.incompatibleProjectIds, 0, resolution.warnings));
                for (PlanItem dependency : analysis.dependencies) {
                    analysis.involvedProjectIds.add(lowerId(dependency.projectId));
                }
                installable.add(analysis);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                resolution.warnings.add(candidate.title + ": " + message);
                resolution.items.add(failedItem(candidate, project, message));
            }
        }

        Collections.sort(installable, new Comparator<CandidateAnalysis>() {
            @Override public int compare(CandidateAnalysis a, CandidateAnalysis b) {
                if (a.score != b.score) return Integer.compare(b.score, a.score);
                int cost = Integer.compare(a.candidate.cost, b.candidate.cost);
                if (cost != 0) return cost;
                return a.candidate.title.compareTo(b.candidate.title);
            }
        });

        Set<String> selectedIds = new HashSet<>();
        Set<String> usedGroups = new HashSet<>();
        int remainingCount = resolution.budgetCount;
        int remainingCost = resolution.budgetCount * 6;
        List<PlanItem> selectedParents = new ArrayList<>();

        for (CandidateAnalysis analysis : installable) {
            String rejected = selectionSkipReason(analysis, selectedIds, usedGroups, installed,
                    remainingCount, remainingCost);
            if (rejected != null) {
                resolution.items.add(skippedOrInstalled(analysis.candidate, analysis.project,
                        ItemState.SKIPPED, rejected, null));
                continue;
            }
            List<PlanItem> dependenciesToInstall = new ArrayList<>();
            int count = 1;
            int cost = analysis.candidate.estimatedCostMb;
            for (PlanItem dependency : analysis.dependencies) {
                String id = lowerId(dependency.projectId);
                if (id.isEmpty() || selectedIds.contains(id) || installed.ids.contains(id)) continue;
                dependenciesToInstall.add(dependency);
                count++;
                cost += dependency.estimatedCostMb;
            }
            if (count > remainingCount || cost > remainingCost) {
                resolution.items.add(skippedOrInstalled(analysis.candidate, analysis.project,
                        ItemState.SKIPPED, "Skipped by the cost-aware selection budget; increasing the number of mods would add more runtime cost.", null));
                continue;
            }
            resolution.items.add(analysis.parent);
            selectedParents.add(analysis.parent);
            selectedIds.add(lowerId(analysis.parent.projectId));
            selectedIds.add(lowerId(analysis.candidate.slug));
            for (String id : analysis.involvedProjectIds) if (!id.isEmpty()) selectedIds.add(id);
            if (analysis.candidate.exclusiveGroup != null) usedGroups.add(analysis.candidate.exclusiveGroup);
            for (PlanItem dependency : dependenciesToInstall) {
                resolution.items.add(dependency);
                selectedIds.add(lowerId(dependency.projectId));
            }
            remainingCount -= count;
            remainingCost -= cost;
            resolution.selectedCount += count;
            resolution.estimatedCostMb += cost;
        }

        // Installed and initially skipped rows were added earlier; keep their natural order after
        // the actionable recommendations by rebuilding the complete list deterministically.
        List<PlanItem> ordered = new ArrayList<>();
        ordered.addAll(selectedParents);
        for (PlanItem item : resolution.items) {
            if (item.state == ItemState.RECOMMENDED && item.dependency) ordered.add(item);
        }
        for (ItemState state : new ItemState[]{ItemState.INSTALLED, ItemState.SKIPPED, ItemState.FAILED}) {
            for (PlanItem item : resolution.items) {
                if (item.state == state) ordered.add(item);
            }
        }
        resolution.items.clear();
        resolution.items.addAll(ordered);
        resolution.canInstall = !resolution.installableItems().isEmpty();
        resolution.summary = resolution.selectedCount + " missing compatible mod(s), "
                + resolution.stateItems(ItemState.INSTALLED).size() + " already installed, "
                + resolution.stateItems(ItemState.SKIPPED).size() + " skipped; estimated static cost "
                + resolution.estimatedCostMb + " MB within a " + resolution.budgetCount
                + "-mod budget.";
        report(progress, "Compatibility resolution complete", totalSteps, totalSteps);
        return resolution;
    }

    private static String selectionSkipReason(CandidateAnalysis analysis, Set<String> selectedIds,
                                              Set<String> usedGroups, InstalledSnapshot installed,
                                              int remainingCount, int remainingCost) {
        if (remainingCount <= 0 || remainingCost <= 0) {
            return "Skipped because the device's cost-aware selection budget is already reached.";
        }
        String parentId = lowerId(analysis.parent.projectId);
        if (selectedIds.contains(parentId) || installed.ids.contains(parentId)) {
            return "Skipped because this project is already selected or installed.";
        }
        if (analysis.candidate.exclusiveGroup != null && usedGroups.contains(analysis.candidate.exclusiveGroup)) {
            return "Skipped because another mod in the same exclusive group was selected.";
        }
        if (intersects(analysis.incompatibleProjectIds, selectedIds)
                || signatureIntersects(analysis.incompatibleProjectIds, installed.signature)) {
            return "Skipped because a Modrinth incompatible-dependency rule matches the selected or installed mods.";
        }
        if (analysis.candidate.sodium && installed.hasSodiumFork) {
            return "Skipped because a Sodium-family renderer mod is already installed.";
        }
        return null;
    }

    private static List<PlanItem> resolveRequiredDependencies(Environment environment,
            DataSource source, PlanItem parent, VersionInfo version, Set<String> visited,
            Set<String> incompatibleIds, int depth, List<String> warnings) throws Exception {
        if (depth > MAX_DEPENDENCY_DEPTH) {
            throw new Exception("Required dependency chain is too deep.");
        }
        List<PlanItem> result = new ArrayList<>();
        if (version.dependencies == null) return result;
        for (DependencyInfo dependency : version.dependencies) {
            if (dependency == null) continue;
            String type = lower(dependency.dependency_type);
            String projectKey = dependency.project_id;
            if ("optional".equals(type) || "embedded".equals(type)) {
                warnings.add("Optional/embedded dependency of " + parent.title
                        + " was not installed separately.");
                continue;
            }
            if ("incompatible".equals(type)) {
                if (projectKey != null && !projectKey.isEmpty()) incompatibleIds.add(lowerId(projectKey));
                continue;
            }
            if (!"required".equals(type)) {
                warnings.add("Unknown dependency type '" + dependency.dependency_type
                        + "' for " + parent.title + " was ignored.");
                continue;
            }
            if (projectKey == null || projectKey.trim().isEmpty()) {
                throw new Exception("Required dependency of " + parent.title + " has no project id.");
            }
            if (visited.contains(lowerId(projectKey))) continue;
            visited.add(lowerId(projectKey));

            ProjectInfo dependencyProject = source.project(projectKey);
            String projectReason = genericProjectReason(dependencyProject);
            if (projectReason != null) {
                throw new Exception("Required dependency " + projectKey + ": " + projectReason);
            }
            VersionInfo dependencyVersion;
            if (dependency.version_id != null && !dependency.version_id.trim().isEmpty()) {
                dependencyVersion = source.version(dependency.version_id);
                String versionReason = versionReason(environment, dependencyProject, dependencyVersion);
                if (versionReason != null) {
                    throw new Exception("Required dependency " + projectKey + ": " + versionReason);
                }
            } else {
                VersionChoice choice = chooseVersion(environment, source, projectKey,
                        dependencyProject, projectKey);
                if (choice.version == null) {
                    throw new Exception("Required dependency " + projectKey + ": " + choice.reason);
                }
                dependencyVersion = choice.version;
            }
            FileSelection selection = selectFile(dependencyVersion);
            PlanItem item = planItem(null, dependencyProject, dependencyVersion, selection,
                    "Required dependency of " + parent.title + ".");
            item.dependency = true;
            result.add(item);
            result.addAll(resolveRequiredDependencies(environment, source, item,
                    dependencyVersion, visited, incompatibleIds, depth + 1, warnings));
        }
        return result;
    }

    private static VersionChoice chooseVersion(Environment environment, DataSource source,
            String queryKey, ProjectInfo project, String label) throws Exception {
        List<VersionInfo> versions = source.versions(queryKey, environment.minecraftVersion,
                environment.loader.modrinthTags());
        if (versions == null || versions.isEmpty()) {
            return new VersionChoice(null, "Modrinth has no compatible " + label + " version for Minecraft "
                    + environment.minecraftVersion + " and " + environment.loader.displayName() + ".");
        }
        VersionInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        String lastReason = null;
        for (VersionInfo version : versions) {
            String reason = versionReason(environment, project, version);
            if (reason != null) {
                lastReason = reason;
                continue;
            }
            int score = versionScore(version);
            if (score > bestScore) {
                bestScore = score;
                best = version;
            }
        }
        if (best == null) {
            return new VersionChoice(null, lastReason == null
                    ? "No stable, hashed, primary JAR was published for this exact version/loader."
                    : lastReason);
        }
        return new VersionChoice(best, null);
    }

    private static String projectSkipReason(Candidate candidate, ProjectInfo project,
                                            Environment environment) {
        String generic = genericProjectReason(project);
        if (generic != null) return generic;
        if (candidate.memoryLeakFix) {
            if (UltraOptimizationDetector.isAtLeast(environment.minecraftVersion, "1.19")) {
                return "Memory Leak Fix is not considered relevant for this newer Minecraft branch.";
            }
            long modified = parseIsoDate(project.date_modified);
            if (modified <= 0 || environment.nowEpochMs - modified > THREE_YEARS_MS) {
                return "Memory Leak Fix was skipped because maintained project metadata could not be established.";
            }
        }
        return null;
    }

    private static String genericProjectReason(ProjectInfo project) {
        if (project == null) return "Modrinth project metadata is unavailable.";
        if (!ACCEPTED_PROJECT_STATUS.contains(lower(project.status))) {
            return "Modrinth project status '" + project.status + "' is not active/listed.";
        }
        String clientSide = lower(project.client_side);
        if ("unsupported".equals(clientSide) || "unknown".equals(clientSide)) {
            return "Modrinth marks this project as unsupported or unknown on the client.";
        }
        return null;
    }

    private static String versionReason(Environment environment, ProjectInfo project,
                                        VersionInfo version) {
        if (version == null) return "Version metadata is unavailable.";
        if (!ACCEPTED_VERSION_STATUS.contains(lower(version.status))) {
            return "Version status '" + version.status + "' is not listed.";
        }
        if (!ACCEPTED_VERSION_TYPES.contains(lower(version.version_type))) {
            return "Version channel '" + version.version_type + "' is not release/beta.";
        }
        if (!containsExact(environment.minecraftVersion, version.game_versions)) {
            return "Version does not declare exact Minecraft version " + environment.minecraftVersion + ".";
        }
        if (!loaderAccepts(environment.loader, project, version)) {
            return "Version does not declare a compatible " + environment.loader.displayName() + " loader.";
        }
        long published = parseIsoDate(version.date_published);
        if (published > 0 && environment.nowEpochMs - published > EIGHT_YEARS_MS) {
            return "Version is too old to be considered maintained.";
        }
        try {
            selectFile(version);
        } catch (Exception e) {
            return e.getMessage();
        }
        return null;
    }

    private static boolean loaderAccepts(Loader loader, ProjectInfo project, VersionInfo version) {
        List<String> declaredLoaders = lowerList(version.loaders);
        if (declaredLoaders.isEmpty()) return false;
        if (loader == Loader.FABRIC) return declaredLoaders.contains("fabric");
        if (loader == Loader.FORGE) return declaredLoaders.contains("forge");
        if (loader == Loader.NEOFORGE) return declaredLoaders.contains("neoforge");
        if (loader == Loader.QUILT) {
            if (declaredLoaders.contains("quilt")) return true;
            boolean projectDeclaresQuilt = project != null && lowerList(project.loaders).contains("quilt");
            return declaredLoaders.contains("fabric") && projectDeclaresQuilt;
        }
        return false;
    }

    private static FileSelection selectFile(VersionInfo version) throws Exception {
        if (version.files == null || version.files.isEmpty()) {
            throw new Exception("No Modrinth file was published.");
        }
        FileInfo chosen = null;
        for (FileInfo file : version.files) {
            if (file != null && file.primary && isJar(file.filename)) { chosen = file; break; }
        }
        if (chosen == null) {
            for (FileInfo file : version.files) {
                if (file != null && isJar(file.filename)) { chosen = file; break; }
            }
        }
        if (chosen == null) throw new Exception("No primary JAR was published.");
        if (chosen.filename == null || !SAFE_FILE_NAME.matcher(chosen.filename).matches()
                || chosen.filename.contains("..")) {
            throw new Exception("Modrinth returned an unsafe JAR filename.");
        }
        if (chosen.size <= 0 || chosen.size > MAX_FILE_BYTES) {
            throw new Exception("Modrinth file size is missing or above the 32 MB safety cap.");
        }
        if (!urlHostAllowed(chosen.url)) {
            throw new Exception("Modrinth file URL is not an allowed HTTPS Modrinth host.");
        }
        String algorithm = null;
        String hash = null;
        if (chosen.hashes != null) {
            for (Map.Entry<String, String> entry : chosen.hashes.entrySet()) {
                String key = lower(entry.getKey());
                if (("sha512".equals(key) || "sha1".equals(key)) && entry.getValue() != null
                        && !entry.getValue().trim().isEmpty()) {
                    algorithm = key;
                    hash = entry.getValue().trim();
                    if ("sha512".equals(key)) break;
                }
            }
        }
        if (algorithm == null || hash == null) {
            throw new Exception("No SHA-512 or SHA-1 hash is available for integrity verification.");
        }
        FileSelection selection = new FileSelection();
        selection.file = chosen;
        selection.fileName = chosen.filename;
        selection.url = chosen.url;
        selection.hashAlgorithm = algorithm;
        selection.hash = hash;
        return selection;
    }

    private static PlanItem planItem(Candidate candidate, ProjectInfo project, VersionInfo version,
                                     FileSelection selection, String reason) {
        PlanItem item = new PlanItem();
        item.candidate = candidate;
        item.projectId = firstNonEmpty(project == null ? null : project.id, version.project_id,
                candidate == null ? null : candidate.slug);
        item.slug = candidate == null
                ? (project == null ? null : project.slug)
                : candidate.slug;
        item.title = candidate != null ? candidate.title
                : firstNonEmpty(project == null ? null : project.title, item.projectId);
        item.description = candidate != null ? candidate.description
                : (project == null ? "Required Modrinth dependency." : project.description);
        item.versionId = version.id;
        item.versionNumber = firstNonEmpty(version.version_number, version.name);
        item.fileName = selection.fileName;
        item.downloadUrl = selection.url;
        item.hashAlgorithm = selection.hashAlgorithm;
        item.hash = selection.hash;
        item.size = selection.file.size;
        item.state = ItemState.RECOMMENDED;
        item.reason = reason;
        item.dependency = candidate == null;
        item.benefit = candidate == null ? 0 : candidate.benefit;
        item.cost = candidate == null ? 0 : candidate.cost;
        item.estimatedCostMb = candidate == null ? 1 : candidate.estimatedCostMb;
        return item;
    }

    private static PlanItem skippedOrInstalled(Candidate candidate, ProjectInfo project,
                                               ItemState state, String reason,
                                               String installedFileName) {
        PlanItem item = new PlanItem();
        item.candidate = candidate;
        item.projectId = project == null ? candidate.slug : firstNonEmpty(project.id, candidate.slug);
        item.slug = candidate.slug;
        item.title = candidate.title;
        item.description = project != null && project.description != null && !project.description.isEmpty()
                ? project.description : candidate.description;
        item.state = state;
        item.reason = reason;
        item.installedFileName = installedFileName;
        item.benefit = candidate.benefit;
        item.cost = candidate.cost;
        item.estimatedCostMb = candidate.estimatedCostMb;
        return item;
    }

    private static PlanItem failedItem(Candidate candidate, ProjectInfo project, String reason) {
        PlanItem item = new PlanItem();
        item.candidate = candidate;
        item.projectId = project == null ? candidate.slug : firstNonEmpty(project.id, candidate.slug);
        item.slug = candidate.slug;
        item.title = candidate.title;
        item.description = candidate.description;
        item.state = ItemState.FAILED;
        item.reason = reason;
        return item;
    }

    private static Map<String, ProjectInfo> loadProjects(DataSource source, List<String> slugs,
                                                         List<String> warnings) {
        Map<String, ProjectInfo> result = new LinkedHashMap<>();
        List<ProjectInfo> batch = null;
        try {
            batch = source.projects(slugs);
        } catch (Exception e) {
            warnings.add("Batch Modrinth project lookup failed; using individual requests: " + message(e));
        }
        if (batch != null) {
            for (ProjectInfo project : batch) {
                if (project == null) continue;
                if (project.slug != null) result.put(lower(project.slug), project);
                if (project.id != null) result.put(lower(project.id), project);
            }
        }
        for (String slug : slugs) {
            if (result.containsKey(lower(slug))) continue;
            try {
                ProjectInfo project = source.project(slug);
                if (project != null) result.put(lower(slug), project);
            } catch (Exception e) {
                warnings.add("Modrinth project " + slug + " is unavailable: " + message(e));
            }
        }
        return result;
    }

    private static void collectDependencyInfo(VersionInfo version, Set<String> incompatibleIds) {
        if (version.dependencies == null) return;
        for (DependencyInfo dependency : version.dependencies) {
            if (dependency != null && "incompatible".equalsIgnoreCase(dependency.dependency_type)
                    && dependency.project_id != null) {
                incompatibleIds.add(lowerId(dependency.project_id));
            }
        }
    }

    private static final class InstalledSnapshot {
        final Set<String> ids = new HashSet<>();
        final String signature;
        final List<String> fileNames = new ArrayList<>();
        boolean hasSodiumFork;

        InstalledSnapshot(Environment environment) {
            StringBuilder builder = new StringBuilder();
            addAll(ids, builder, environment.installedProjectIds);
            addAll(ids, builder, environment.installedModIds);
            addAll(ids, builder, environment.installedFileNames);
            for (String fileName : environment.installedFileNames) {
                if (fileName == null) continue;
                fileNames.add(fileName);
                if (UltraOptimizationDetector.isSodiumForkToken(fileName)) hasSodiumFork = true;
            }
            for (String id : environment.installedModIds) {
                if (UltraOptimizationDetector.isSodiumForkToken(id)) hasSodiumFork = true;
            }
            signature = builder.toString();
        }

        String matchingFileName(Candidate candidate) {
            for (String fileName : fileNames) {
                String lower = fileName.toLowerCase(Locale.US);
                for (String token : candidate.filenameTokens) {
                    if (lower.contains(token)) return fileName;
                }
            }
            return null;
        }

        private static void addAll(Set<String> ids, StringBuilder builder, Set<String> values) {
            for (String value : values) {
                if (value == null) continue;
                String lower = value.toLowerCase(Locale.US);
                ids.add(lower);
                builder.append(lower).append('\n');
            }
        }
    }

    private static InstalledSnapshot installedSnapshot(Environment environment) {
        return new InstalledSnapshot(environment);
    }

    private static String installedReason(Candidate candidate, ProjectInfo project,
                                          InstalledSnapshot installed) {
        if (installed.ids.contains(lowerId(candidate.slug))) return "Already installed in this Minecraft installation.";
        if (project != null) {
            if (installed.ids.contains(lowerId(project.id))) return "Already installed in this Minecraft installation.";
            if (installed.ids.contains(lowerId(project.slug))) return "Already installed in this Minecraft installation.";
        }
        for (String token : candidate.filenameTokens) {
            if (installed.ids.contains(token)) return "Already installed in this Minecraft installation.";
            if (installed.signature.contains(token)) return "A matching mod file is already installed; skipped to avoid duplicates.";
        }
        if (candidate.sodium) {
            for (String token : installed.ids) {
                if (UltraOptimizationDetector.isSodiumForkToken(token)) {
                    return "A Sodium-family renderer mod is already installed; Sodium is skipped to avoid a conflict.";
                }
            }
        }
        return null;
    }

    private static int budgetCount(Environment environment) {
        if (environment == null || environment.totalRamMb <= 0) return 3;
        if (environment.is32Bit || environment.totalRamMb <= 3072) return 2;
        if (environment.totalRamMb <= 4096) return 3;
        if (environment.totalRamMb <= 6144) return 4;
        return 5;
    }

    private static int versionScore(VersionInfo version) {
        int score = 0;
        String type = lower(version.version_type);
        if ("release".equals(type)) score += 300;
        else score += 150;
        if (version.featured) score += 40;
        long published = parseIsoDate(version.date_published);
        if (published > 0) score += (int) Math.min(100L, published / 100000000000L);
        if (version.id != null) score += Math.min(20, version.id.length());
        return score;
    }

    private static boolean urlHostAllowed(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            String scheme = lower(uri.getScheme());
            String host = lower(uri.getHost());
            return "https".equals(scheme)
                    && ("cdn.modrinth.com".equals(host) || "api.modrinth.com".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isJar(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.US).endsWith(".jar");
    }

    private static boolean containsExact(String needle, List<String> values) {
        if (needle == null || values == null) return false;
        for (String value : values) if (needle.equalsIgnoreCase(value)) return true;
        return false;
    }

    private static List<String> lowerList(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (String value : values) if (value != null) result.add(value.toLowerCase(Locale.US));
        return result;
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        for (String value : left) if (!value.isEmpty() && right.contains(value)) return true;
        return false;
    }

    private static boolean signatureIntersects(Set<String> ids, String signature) {
        for (String id : ids) {
            if (!id.isEmpty() && signature.contains(id)) return true;
        }
        return false;
    }

    private static long parseIsoDate(String value) {
        if (value == null || value.trim().length() < 10) return -1L;
        String text = value.trim();
        if (text.length() >= 19) text = text.substring(0, 19);
        else if (text.endsWith("Z")) text = text.substring(0, text.length() - 1);
        if (text.endsWith("Z")) text = text.substring(0, text.length() - 1);
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                format.setLenient(false);
                return format.parse(text).getTime();
            } catch (ParseException ignored) {
            }
        }
        return -1L;
    }

    private static void addFatal(Resolution resolution, String message) {
        resolution.errors.add(message);
    }

    private static String firstError(Resolution resolution) {
        return resolution.errors.isEmpty() ? "No compatible mods were selected." : resolution.errors.get(0);
    }

    private static void report(ProgressCallback callback, String message, int completed, int total) {
        if (callback != null) callback.onProgress(message, completed, total);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String lower(String value) {
        return UltraOptimizationModels.lower(value);
    }

    private static String lowerId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }
}
