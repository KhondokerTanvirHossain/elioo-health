#!/bin/bash

# OCR Test Runner Script for MedScribe AI
# This script helps run OCR tests with proper configuration

set -e  # Exit on error

echo "======================================"
echo "  MedScribe AI - OCR Test Runner"
echo "======================================"
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

# Check if AWS credentials are configured
check_aws_credentials() {
    if [ -f "$HOME/.aws/credentials" ] || [ ! -z "$AWS_ACCESS_KEY_ID" ]; then
        print_success "AWS credentials found"
        return 0
    else
        print_warning "AWS credentials not found"
        print_info "Run 'aws configure' to set up credentials"
        return 1
    fi
}

# Check if test image exists
check_test_image() {
    if [ -f "src/test/resources/test-images/blood-test-report.png" ]; then
        print_success "Test image found"
        return 0
    else
        print_warning "Test image not found (will use fallback image)"
        print_info "Add image to: src/test/resources/test-images/blood-test-report.png"
        return 1
    fi
}

# Menu
show_menu() {
    echo ""
    echo "Select test type to run:"
    echo ""
    echo "  1) Unit Tests (No AWS required - Fast)"
    echo "  2) Integration Tests (Requires AWS credentials)"
    echo "  3) All OCR Tests"
    echo "  4) Single Integration Test (testExtractMedicalData_HappyPath)"
    echo "  5) Check Prerequisites"
    echo "  6) Download Dependencies"
    echo "  0) Exit"
    echo ""
    read -p "Enter choice [0-6]: " choice
}

# Run unit tests
run_unit_tests() {
    echo ""
    print_info "Running Unit Tests (with mocked AWS client)..."
    echo ""
    ./gradlew test --tests TextractAdapterTest
    if [ $? -eq 0 ]; then
        print_success "Unit tests passed!"
    else
        print_error "Unit tests failed"
        exit 1
    fi
}

# Run integration tests
run_integration_tests() {
    echo ""
    if ! check_aws_credentials; then
        print_error "Cannot run integration tests without AWS credentials"
        exit 1
    fi

    print_info "Running Integration Tests (calling real AWS Textract)..."
    echo ""
    ./gradlew test --tests TextractAdapterIntegrationTest --info
    if [ $? -eq 0 ]; then
        print_success "Integration tests passed!"
    else
        print_error "Integration tests failed"
        exit 1
    fi
}

# Run all OCR tests
run_all_tests() {
    echo ""
    print_info "Running all OCR tests..."
    echo ""

    print_info "Step 1/2: Unit Tests"
    ./gradlew test --tests TextractAdapterTest

    if [ $? -eq 0 ]; then
        print_success "Unit tests passed!"

        if check_aws_credentials; then
            print_info "Step 2/2: Integration Tests"
            ./gradlew test --tests TextractAdapterIntegrationTest
            if [ $? -eq 0 ]; then
                print_success "All tests passed!"
            else
                print_error "Integration tests failed"
                exit 1
            fi
        else
            print_warning "Skipping integration tests (no AWS credentials)"
        fi
    else
        print_error "Unit tests failed"
        exit 1
    fi
}

# Run single integration test
run_single_test() {
    echo ""
    if ! check_aws_credentials; then
        print_error "Cannot run integration test without AWS credentials"
        exit 1
    fi

    print_info "Running single integration test: testExtractMedicalData_HappyPath..."
    echo ""
    ./gradlew test --tests TextractAdapterIntegrationTest.testExtractMedicalData_HappyPath --info
    if [ $? -eq 0 ]; then
        print_success "Test passed!"
    else
        print_error "Test failed"
        exit 1
    fi
}

# Check prerequisites
check_prerequisites() {
    echo ""
    print_info "Checking prerequisites..."
    echo ""

    # Check Java
    if command -v java &> /dev/null; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
        print_success "Java installed: $JAVA_VERSION"
    else
        print_error "Java not found"
    fi

    # Check Gradle
    if [ -f "./gradlew" ]; then
        print_success "Gradle wrapper found"
    else
        print_error "Gradle wrapper not found"
    fi

    # Check AWS CLI
    if command -v aws &> /dev/null; then
        AWS_VERSION=$(aws --version 2>&1)
        print_success "AWS CLI installed: $AWS_VERSION"
    else
        print_warning "AWS CLI not found (optional)"
    fi

    # Check credentials
    check_aws_credentials

    # Check test image
    check_test_image

    # Check build.gradle dependencies
    if grep -q "software.amazon.awssdk:textract" build.gradle; then
        print_success "AWS Textract dependency configured in build.gradle"
    else
        print_error "AWS Textract dependency not found in build.gradle"
    fi

    echo ""
    print_info "Prerequisites check complete"
}

# Download dependencies
download_dependencies() {
    echo ""
    print_info "Downloading AWS SDK dependencies..."
    echo ""
    ./gradlew build -x test
    if [ $? -eq 0 ]; then
        print_success "Dependencies downloaded successfully!"
    else
        print_error "Failed to download dependencies"
        exit 1
    fi
}

# Main loop
while true; do
    show_menu
    case $choice in
        1)
            run_unit_tests
            ;;
        2)
            run_integration_tests
            ;;
        3)
            run_all_tests
            ;;
        4)
            run_single_test
            ;;
        5)
            check_prerequisites
            ;;
        6)
            download_dependencies
            ;;
        0)
            echo ""
            print_info "Exiting..."
            exit 0
            ;;
        *)
            print_error "Invalid choice"
            ;;
    esac

    echo ""
    read -p "Press Enter to continue..."
done
