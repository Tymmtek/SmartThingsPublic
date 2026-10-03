#!/usr/bin/env bash
# Installs ComfyUI + ComfyUI-Manager on a Linux machine with an NVIDIA GPU.
# Run this on your own GPU machine (or a rented GPU box), not in the Claude cloud container (no GPU there).
set -euo pipefail

INSTALL_DIR="${1:-$HOME/ComfyUI}"

command -v git >/dev/null || { echo "git is required"; exit 1; }
command -v python3 >/dev/null || { echo "python3 (3.10-3.12) is required"; exit 1; }
nvidia-smi >/dev/null 2>&1 || echo "WARNING: no NVIDIA GPU detected; generation will be extremely slow or fail."

git clone https://github.com/comfyanonymous/ComfyUI.git "$INSTALL_DIR"
cd "$INSTALL_DIR"

python3 -m venv venv
source venv/bin/activate
pip install --upgrade pip
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu128
pip install -r requirements.txt

# Node manager: lets you install video / face / LoRA nodes from the UI
git clone https://github.com/Comfy-Org/ComfyUI-Manager.git custom_nodes/ComfyUI-Manager
pip install -r custom_nodes/ComfyUI-Manager/requirements.txt

cat <<EOF

ComfyUI installed in $INSTALL_DIR

Start it:   cd $INSTALL_DIR && source venv/bin/activate && python main.py --listen 127.0.0.1
Open:       http://127.0.0.1:8188

Next steps (done by you, in the UI / model folders):
  1. Put a checkpoint (e.g. an SDXL or Flux model you have the licence for) in models/checkpoints/
  2. For video, install a model such as Wan 2.x via Manager > Model Manager / the built-in templates
  3. To get consistent images of yourself, train a LoRA on your own photos (e.g. with ai-toolkit or kohya_ss)
     and put it in models/loras/. Only use photos of yourself.
EOF
