# Homebrew formula for Atmosphere CLI
# Install: brew install Atmosphere/tap/atmosphere
#
# This formula installs the `atmosphere` CLI tool for running samples,
# scaffolding new projects, and exploring the Atmosphere framework.

class Atmosphere < Formula
  desc "CLI for the Atmosphere real-time Java framework — run samples, scaffold projects"
  homepage "https://github.com/Atmosphere/atmosphere"
  url "https://github.com/Atmosphere/atmosphere/archive/refs/tags/v4.0.14.tar.gz"
  # sha256 "UPDATE_WITH_ACTUAL_SHA256_AFTER_RELEASE"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    # Install the CLI script
    bin.install "cli/atmosphere"

    # Install samples.json alongside
    (share/"atmosphere").install "cli/samples.json"

    # Create a wrapper that sets JAVA_HOME and knows where samples.json is
    (bin/"atmosphere").unlink
    (bin/"atmosphere").write <<~EOS
      #!/bin/sh
      export JAVA_HOME="#{Formula["openjdk@21"].opt_prefix}"
      export PATH="$JAVA_HOME/bin:$PATH"
      exec sh "#{share}/atmosphere/atmosphere.sh" "$@"
    EOS

    # Install the actual script as atmosphere.sh
    (share/"atmosphere").install "cli/atmosphere" => "atmosphere.sh"

    # Make sure samples.json is findable (the script checks its own directory)
    chmod 0755, bin/"atmosphere"
    chmod 0755, share/"atmosphere/atmosphere.sh"
  end

  def caveats
    <<~EOS
      Atmosphere CLI is installed! Get started:

        atmosphere list                    # See all samples
        atmosphere run spring-boot-chat    # Run a sample
        atmosphere new my-app              # Create a project

      Samples require Java 21+ (installed as a dependency).
      AI samples need API keys — see: atmosphere info <sample>
    EOS
  end

  test do
    assert_match "Atmosphere", shell_output("#{bin}/atmosphere version")
    assert_match "spring-boot-chat", shell_output("#{bin}/atmosphere list")
  end
end
