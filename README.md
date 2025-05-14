# CurrencyAI

The project is a Webcam Currency Counter system that uses a Java backend (with Spark Java for HTTP endpoints) and a web-based frontend (HTML &JavaScript) to control a webcam process for counting currency.

## Prerequisites

- [Apache Maven](https://maven.apache.org/) installed
- [Apache Spark](https://spark.apache.org/) running and accessible

## Setup

1. **Navigate to the project directory:**
   ```sh
   cd WebcamCurrencyCounter
   ```

2. **Build the project using Maven:**
   ```sh
   mvn clean install
   ```

## Usage

1. **Start Apache Spark**  
   Ensure that your Spark instance is running before starting the backend.

2. **Run the backend application**  
   After building, follow your usual process to start the backend.

## Notes

- The backend will not function unless Spark is online.
- For any issues, please check your Spark and Maven installations.

Thank you for using CurrencyAI
