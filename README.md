# HKS Layout Studio

A Morpheus plugin to see, change, back up and restore cluster layouts.
It works offline. Clusters built from its layouts never need the internet.

## What it does

- Shows a layout as a flow: master nodes, worker nodes, extra steps, add-ons. Click a card to see it.
- Changes your own layouts: name, description, node counts, scripts and files, extra steps, add-ons.
- Adds add-ons from a YAML file, a Helm chart or a Git link. They are saved in Morpheus.
- Makes an editable copy of a built-in HKS layout. The copy keeps the Kubernetes version and add-ons.
- Exports layouts to YAML. Several layouts come as a zip, one file each.
- Restores a YAML or zip file. You see every change first. If it fails halfway, it is undone.

## Walkthrough

[![Walkthrough: from a built-in layout to a running cluster](https://github.com/NixndME/morpheus-HKS-layout-studio-plugin/releases/download/v0.1.42/hks-layout-studio-preview.gif)](https://github.com/NixndME/morpheus-HKS-layout-studio-plugin/releases/download/v0.1.42/hks-layout-studio-walkthrough.mp4)

[Watch the full walkthrough (MP4, about 15 minutes)](https://github.com/NixndME/morpheus-HKS-layout-studio-plugin/releases/download/v0.1.42/hks-layout-studio-walkthrough.mp4):
upload the plugin, download a built-in layout, upload it as a new layout, add Apache (YAML), Loki (Helm chart)
and guestbook (GitHub), create a cluster from it in Infrastructure > Clusters, and see the add-ons running.
The cluster install is shown in fast forward.

## Install

1. Administration > Integrations > Plugins > Add, upload `morpheus-hks-layout-studio-plugin.jar`.
2. Administration > Integrations > New Integration > HKS Layout Studio.
3. Administration > Roles: set "HKS Layout Studio" to Read (view and export) or Full (change and restore).

To upgrade, upload the new jar over the old one. Role settings stay.

## Use

Open Administration > Integrations > HKS Layout Studio.

- Click a layout name to open its flow. Esc closes a card, a second Esc goes back.
- Built-in layouts are read only. Use "Make an editable copy".
- "+ Add script or file" adds a step to your own node type.
- Built-in node types can not take extra scripts in Morpheus. Use "Extra steps": they run on every node.
- "+ Add an add-on":
  - YAML file: drop one or more files.
  - Helm chart: drop a `.tgz` and, if you like, a values file. It is turned into plain YAML now,
    so clusters need no Helm.
  - Git link: GitHub, GitLab, Gitea or any Git server, with an optional token. It is fetched once now.
  - Already in Morpheus: pick an existing add-on.
- The add-on card lists the container images it needs. For offline sites, put them in your local registry.
- Export: tick layouts, click Export. One layout is a YAML file, several are a zip.
- Restore: choose a YAML or zip file, click Preview, check the list. Under "Save as" keep the name,
  or type a new one to make a new layout. Then click Restore.

## What is in a backup file

```yaml
layouts:
- name: Lab HKS 1.35 Small
  basedOn: kubernetes-1.35-ubuntu-24.04-morpheus-amd64-single
  packages:
  - kubernetes-calico-3-31-1-package
  - ls-apache-2-4
  workflows:
  - Lab HKS 1.35 Small steps
  nodes:
  - role: master
    count: 1
    nodeType: builtin:kubernetes-ubuntu-24.04-morpheus-amd64
  - role: worker
    count: 2
    nodeType: builtin:kubernetes-ubuntu-24.04-worker-morpheus-amd64
addons:
- code: ls-apache-2-4
  name: apache 2.4
  yaml:
  - content: |
      apiVersion: apps/v1
      kind: Deployment
      ...
workflows:
- name: Lab HKS 1.35 Small steps
  steps:
  - name: write marker
    phase: postProvision
    content: |
      echo "made by HKS Layout Studio" > /etc/ls-marker
```

- `basedOn` sets the Kubernetes version. Change it to pick another built-in version.
- `packages` are the add-ons. Remove a line to leave one out.
- Your own scripts, files, inputs, add-ons and steps are saved in full. Built-in ones by name.

## Good to know

- Restore needs the same Morpheus version. Built-in items must exist there.
- Charts that read the live cluster (Helm `lookup`) get a warning; those parts are empty.
- Step names must be unique in Morpheus. A taken name gets a number, like "write marker 2".
- Tested on Morpheus 9.0.2 with HKS 1.35 manual layouts.

## Build

Needs JDK 17 and Gradle 8.5.

```
gradle -p plugin shadowJar
```

The jar is in `plugin/build/libs/`. The build downloads Helm once (pinned version, checked) and packs it in,
so charts can be turned into YAML without internet later. Helm is under the Apache 2.0 license,
see `helm/LICENSE` inside the jar.
