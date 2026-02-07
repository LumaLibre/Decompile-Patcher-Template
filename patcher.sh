#!/bin/bash

# Quick patcher script
# Usage: ./patcher.sh <command> <args?>

set -e

# Find gradle
if [ -f "./gradlew" ]; then
    GRADLE="./gradlew"
elif command -v gradle &> /dev/null; then
    GRADLE="gradle"
else
    echo "Error: Can't find gradle. Run this from the project root."
    exit 1
fi

case "$1" in
    init)
        echo "=== Initializing ==="
        $GRADLE decompileAndApplyPatches
        echo "Done!"
        ;;

    fresh)
        echo "=== Fresh Start ==="
        echo "This will wipe everything and decompile all over again."
        read -p "Continue? [y/N] " confirm
        if [[ "$confirm" =~ ^[Yy]$ ]]; then
            $GRADLE cleanDistributedSources cleanGenerated
            $GRADLE decompileAndApplyPatches
            echo "Finished!"
        fi
        ;;

    status|s)
        $GRADLE patchStatus
        ;;

    create|c)
        if [ -z "$2" ]; then
            read -p "Patch name: " name
        else
            name="$2"
        fi
        $GRADLE createPatch -PpatchName="$name"
        ;;

    apply|a)
        if [ -z "$2" ]; then
            $GRADLE listPatches
            echo ""
            read -p "Patch name: " name
        else
            name="$2"
        fi
        $GRADLE applyPatch -PpatchName="$name"
        ;;

    apply-all|aa)
        $GRADLE applyAllPatches
        ;;

    list|l)
        $GRADLE listPatches
        ;;

    inspect|i)
        $GRADLE inspectDecompiledStructure
        ;;

    clean)
        echo "Clean options:"
        echo "  1) Module sources"
        echo "  2) Generated/decompiled"
        echo "  3) Both"
        read -p "Choice: " choice
        case $choice in
            1) $GRADLE cleanDistributedSources ;;
            2) $GRADLE cleanGenerated ;;
            3) $GRADLE cleanDistributedSources cleanGenerated ;;
        esac
        ;;

    save)
        $GRADLE patchStatus
        echo ""
        read -p "Patch name (or blank to cancel): " name
        if [ -n "$name" ]; then
            $GRADLE createPatch -PpatchName="$name"
        fi
        ;;

    *)
        echo "Patcher commands:"
        echo ""
        echo "  init        - First time setup (decompile + distribute + patches)"
        echo "  fresh       - Wipe and start over"
        echo ""
        echo "  status, s   - Check what's been changed and what you can edit with a new patch"
        echo "  save        - Check status then save as patch"
        echo "  create, c   - Create a patch"
        echo "  apply, a    - Apply a patch"
        echo "  apply-all   - Apply all patches"
        echo "  list, l     - List patches"
        echo ""
        echo "  inspect, i  - Show decompiled structure"
        echo "  clean       - Clean up sources"
        echo ""
        echo "Examples:"
        echo "  ./patcher.sh init"
        echo "  ./patcher.sh status"
        echo "  ./patcher.sh create 000-my-patch"
        echo "  ./patcher.sh save"
        ;;
esac