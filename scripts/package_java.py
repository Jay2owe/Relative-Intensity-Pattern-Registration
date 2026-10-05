"""Build the public Java plugin and bundle its runtime for wheel/sdist packaging."""
import shutil
import subprocess
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    subprocess.run([shutil.which("mvn") or "mvn", "package", "-Denforcer.skip=true"], cwd=root, check=True)
    destination = root / "src/ripr/jars"
    destination.mkdir(exist_ok=True)
    shutil.copy2(root / "target/RelativeIntensityPatternRegistration-0.3.0.jar", destination)
    shutil.copy2(root / "src/main/resources/ripr/longitudinal/ij-1.54p.jar", destination)


if __name__ == "__main__":
    main()
