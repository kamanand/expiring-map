#!/bin/bash
set -e

# Resolve JDK 25 path if not already configured in environment
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    if [ -d "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home" ]; then
        export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
    elif [ -x "/usr/libexec/java_home" ]; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    fi
fi

if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

M2_REPO="$HOME/.m2/repository"
TEST_CP="target/classes:target/test-classes:\
$M2_REPO/org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar:\
$M2_REPO/org/junit/jupiter/junit-jupiter-params/5.10.2/junit-jupiter-params-5.10.2.jar:\
$M2_REPO/org/junit/jupiter/junit-jupiter-engine/5.10.2/junit-jupiter-engine-5.10.2.jar:\
$M2_REPO/org/junit/platform/junit-platform-commons/1.10.2/junit-platform-commons-1.10.2.jar:\
$M2_REPO/org/junit/platform/junit-platform-engine/1.10.2/junit-platform-engine-1.10.2.jar:\
$M2_REPO/org/junit/platform/junit-platform-launcher/1.10.2/junit-platform-launcher-1.10.2.jar:\
$M2_REPO/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar:\
$M2_REPO/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar:\
$M2_REPO/org/assertj/assertj-core/3.25.3/assertj-core-3.25.3.jar:\
$M2_REPO/net/bytebuddy/byte-buddy/1.14.11/byte-buddy-1.14.11.jar"

MODE="${1:-demo}"

if [ "$MODE" = "clean" ]; then
    echo "Cleaning target directory..."
    rm -rf target
    echo "Clean complete."
    exit 0
fi

mkdir -p target/classes target/test-classes

echo "Compiling main sources..."
javac -d target/classes src/main/java/com/expiringmap/*.java

JAR_FILE="target/expiring-map-1.0.0.jar"
SOURCES_JAR_FILE="target/expiring-map-1.0.0-sources.jar"

package_library() {
    echo "Packaging ExpiringMap library JAR..."
    jar --create --file "$JAR_FILE" --main-class com.expiringmap.ExpiringMapDemo -C target/classes .
    jar --create --file "$SOURCES_JAR_FILE" -C src/main/java .
    echo "Library packaged successfully at $JAR_FILE"
    
    # Sync with local Maven repository if present
    M2_TARGET="$M2_REPO/com/expiringmap/expiring-map/1.0.0"
    if [ -d "$M2_TARGET" ]; then
        echo "Updating local Maven repository cache at $M2_TARGET..."
        cp "$JAR_FILE" "$M2_TARGET/"
        cp "$SOURCES_JAR_FILE" "$M2_TARGET/"
    fi
}

if [ "$MODE" = "build" ] || [ "$MODE" = "package" ] || [ "$MODE" = "jar" ]; then
    package_library
    echo "Build completed successfully."
elif [ "$MODE" = "test" ]; then
    echo "Compiling test sources..."
    javac -cp "$TEST_CP" -d target/test-classes src/test/java/com/expiringmap/*.java
    echo "Running JUnit 5 test suite..."

    RUNNER_SRC=$(mktemp /tmp/JUnitRunner.XXXXXX.java)
    cat << 'EOF' > "$RUNNER_SRC"
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public class JUnitRunner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectPackage("com.expiringmap"))
            .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out));
        if (summary.getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}
EOF
    java -cp "$TEST_CP" "$RUNNER_SRC"
    rm -f "$RUNNER_SRC"
elif [ "$MODE" = "all" ] || [ "$MODE" = "verify" ]; then
    echo "Compiling test sources..."
    javac -cp "$TEST_CP" -d target/test-classes src/test/java/com/expiringmap/*.java
    echo "Running JUnit 5 test suite..."

    RUNNER_SRC=$(mktemp /tmp/JUnitRunner.XXXXXX.java)
    cat << 'EOF' > "$RUNNER_SRC"
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public class JUnitRunner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectPackage("com.expiringmap"))
            .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out));
        if (summary.getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}
EOF
    java -cp "$TEST_CP" "$RUNNER_SRC"
    rm -f "$RUNNER_SRC"

    package_library
    echo "Running ExpiringMap live demo from built JAR..."
    java -jar "$JAR_FILE"
else
    package_library
    echo "Running ExpiringMap live demo from built JAR..."
    java -jar "$JAR_FILE"
fi
