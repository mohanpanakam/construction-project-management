#!/bin/bash
# EC2 user-data script — Ubuntu (arm64, e.g. t4g/m7g instance types).
# Installs Docker + Compose plugin, adds a swapfile (helpful on small
# instances), and marks completion so you can poll for readiness.
#
# Usage: paste this file's contents into the "User data" field when
# launching the EC2 instance (Ubuntu 22.04/24.04 arm64 AMI).
set -e
apt-get update -y
apt-get install -y ca-certificates curl gnupg git
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
usermod -aG docker ubuntu
systemctl enable docker
systemctl start docker
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
touch /home/ubuntu/user-data-done.txt

