# Homebrew formula for Atmosphere CLI
# Install: brew install Atmosphere/tap/atmosphere
#
# This formula installs the `atmosphere` CLI tool for running samples,
# scaffolding new projects, and exploring the Atmosphere framework.
# Samples are built from source on first run and cached locally.

class Atmosphere < Formula
  desc "CLI for the Atmosphere real-time Java framework — run samples, scaffold projects"
  homepage "https://github.com/Atmosphere/atmosphere"
  url "https://github.com/Atmosphere/atmosphere/archive/refs/tags/atmosphere-4.0.36.tar.gz"
  sha256 "dbe3616be8f8eeea5f040e9be92fc518471de58b0a3c8587177883c35cbcee78"
  license "Apache-2.0"
  version "4.0.36"

  depends_on "openjdk@21"

  def install
    # Install the actual CLI script
    bin.install "cli/atmosphere" => "atmosphere-cli.sh"

    # Install samples.json alongside the script
    (share/"atmosphere").install "cli/samples.json"

    # Create a wrapper that sets JAVA_HOME and links samples.json
    (bin/"atmosphere").write <<~EOS
      #!/bin/sh
      export JAVA_HOME="#{Formula["openjdk@21"].opt_prefix}"
      export PATH="$JAVA_HOME/bin:$PATH"
      exec sh "#{bin}/atmosphere-cli.sh" "$@"
    EOS

    chmod 0755, bin/"atmosphere"
    chmod 0755, bin/"atmosphere-cli.sh"

    # Symlink samples.json next to the CLI script so it's auto-discovered
    ln_sf share/"atmosphere/samples.json", bin/"samples.json"
  end

  def caveats
    <<~EOS
      Atmosphere CLI is installed! Get started:

        atmosphere list                    # See all samples
        atmosphere run spring-boot-chat    # Build & run a sample
        atmosphere new my-app              # Create a project

      Samples require Java 21+ (installed as a dependency).
      First run builds from source (~1-2 min); subsequent runs use cached JARs.
      AI samples need API keys — see: atmosphere info <sample>
    EOS
  end

  test do
    assert_match "Atmosphere", shell_output("#{bin}/atmosphere version")
    assert_match "spring-boot-chat", shell_output("#{bin}/atmosphere list")
  end
end
