import os
import subprocess

def generate_protobuf_code(proto_dir, autogen_dir):
  """Generates Python, Java, and C++ code from protobuf files.

  Args:
    proto_dir: The directory containing the .proto files.
    autogen_dir: The directory to output the generated code.
  """

  # Create the autogen directory if it doesn't exist
  os.makedirs(autogen_dir, exist_ok=True)

  # Find all .proto files in the proto directory
  proto_files = [f for f in os.listdir(proto_dir) if f.endswith(".proto")]

  # Generate code for each .proto file
  for proto_file in proto_files:
    proto_path = os.path.join(proto_dir, proto_file)

    # Generate Python code
    subprocess.run([
        "protoc",
        f"--python_out={autogen_dir}",
        proto_path
    ])

    # Generate Java code
    subprocess.run([
        "protoc",
        f"--java_out={autogen_dir}",
        proto_path
    ])

    # Generate C++ code
    subprocess.run([
        "protoc",
        f"--cpp_out={autogen_dir}",
        proto_path
    ])

if __name__ == "__main__":
  proto_dir = "./protobuf"
  autogen_dir = "./autogen"
  generate_protobuf_code(proto_dir, autogen_dir)