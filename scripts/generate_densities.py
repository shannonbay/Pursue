import os
import glob
import sys
from PIL import Image

def generate_densities(source_dir, dest_res_path, base_size=64):
    """
    Generates Android density-specific drawables from high-resolution source icons.
    """
    # Target densities and their scale factors (relative to mdpi)
    densities = {
        "drawable-mdpi": base_size,
        "drawable-hdpi": int(base_size * 1.5),
        "drawable-xhdpi": base_size * 2,
        "drawable-xxhdpi": base_size * 3,
        "drawable-xxxhdpi": base_size * 4
    }

    # Find all ic_icon_* files in the source directory
    pattern = os.path.join(source_dir, "ic_icon*")
    files = glob.glob(pattern)
    
    if not files:
        print(f"No ic_icon* files found in {source_dir}")
        return

    print(f"Generating densities for {len(files)} icons with base size {base_size}dp...")
    print(f"Source: {source_dir}")
    print(f"Destination Resources: {dest_res_path}")

    for file_path in files:
        file_name = os.path.basename(file_path)
        print(f"Processing {file_name}...")
        
        try:
            with Image.open(file_path) as img:
                if img.mode != 'RGBA':
                    img = img.convert('RGBA')
                
                for density_dir, size in densities.items():
                    target_dir = os.path.join(dest_res_path, density_dir)
                    if not os.path.exists(target_dir):
                        os.makedirs(target_dir)
                    
                    target_file_name = os.path.splitext(file_name)[0] + ".png"
                    target_path = os.path.join(target_dir, target_file_name)
                    
                    resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
                    resized_img.save(target_path, "PNG")
                    print(f"  -> {density_dir}/{target_file_name} ({size}x{size})")
                    
        except Exception as e:
            print(f"  Error processing {file_name}: {e}")

    print("\nDensity generation complete.")

if __name__ == "__main__":
    # Default paths
    src = "images"
    dest = "pursue-app/app/src/main/res"
    size = 64
    
    if len(sys.argv) > 1:
        src = sys.argv[1]
    if len(sys.argv) > 2:
        dest = sys.argv[2]
    if len(sys.argv) > 3:
        try:
            size = int(sys.argv[3])
        except ValueError:
            pass
            
    generate_densities(src, dest, size)
