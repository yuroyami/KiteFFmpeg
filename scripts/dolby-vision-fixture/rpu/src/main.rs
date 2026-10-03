// Writes the RPUs of the Dolby Vision fixture, one per frame, as HEVC NAL units that dovi_tool
// inject-rpu takes. Each RPU reshapes every way a profile 5 stream can: luma through three
// second-order polynomial pieces, chroma through third-order MMR over all three components. The
// two frames differ in their luma curve and their level 1 brightness, so a reader that keeps the
// first frame's RPU is caught.
//
// Usage: dolby-vision-fixture-rpu <out.bin>
use anyhow::Result;
use dolby_vision::rpu::generate::GenerateConfig;
use dolby_vision::rpu::rpu_data_mapping::{DoviMMRCurve, DoviMappingMethod, DoviPolynomialCurve, DoviReshapingCurve};
use tinyvec::ArrayVec;

const DENOM: u64 = 23;

const CONFIG: &str = r#"{
    "profile": "5",
    "length": 2,
    "source_min_pq": 7,
    "source_max_pq": 3079,
    "level6": {
        "max_display_mastering_luminance": 1000,
        "min_display_mastering_luminance": 1,
        "max_content_light_level": 943,
        "max_frame_average_light_level": 400
    },
    "shots": [
        { "start": 0, "duration": 1, "metadata_blocks": [ { "Level1": { "min_pq": 0, "max_pq": 2081, "avg_pq": 819 } } ] },
        { "start": 1, "duration": 1, "metadata_blocks": [ { "Level1": { "min_pq": 7, "max_pq": 3079, "avg_pq": 1365 } } ] }
    ]
}"#;

/// A real coefficient as the integer and fractional parts an RPU carries.
fn split(v: f64) -> (i64, u64) {
    let scaled = (v * (1u64 << DENOM) as f64).round() as i64;
    (scaled.div_euclid(1 << DENOM), scaled.rem_euclid(1 << DENOM) as u64)
}

/// An RPU stores the first pivot and then the step to each next one.
fn deltas(pivots: &[u16]) -> Vec<u16> {
    let mut out = vec![pivots[0]];
    for w in pivots.windows(2) {
        out.push(w[1] - w[0]);
    }
    out
}

fn polynomial(pivots: &[u16], pieces: &[[f64; 3]]) -> DoviReshapingCurve {
    let (mut ints, mut fracs) = (Vec::new(), Vec::new());
    for piece in pieces {
        let (mut i, mut f) = (ArrayVec::<[i64; 3]>::new(), ArrayVec::<[u64; 3]>::new());
        for &c in piece {
            let (a, b) = split(c);
            i.push(a);
            f.push(b);
        }
        ints.push(i);
        fracs.push(f);
    }
    DoviReshapingCurve {
        num_pivots_minus2: (pivots.len() - 2) as u64,
        pivots: deltas(pivots),
        mapping_idc: DoviMappingMethod::Polynomial,
        polynomial: Some(DoviPolynomialCurve {
            poly_order_minus1: vec![1; pieces.len()],
            linear_interp_flag: vec![false; pieces.len()],
            poly_coef_int: ints,
            poly_coef: fracs,
        }),
        mmr: None,
    }
}

fn mmr(constant: f64, orders: &[[f64; 7]]) -> DoviReshapingCurve {
    let (ci, cf) = split(constant);
    let (mut oi, mut of) = (ArrayVec::<[ArrayVec<[i64; 7]>; 3]>::new(), ArrayVec::<[ArrayVec<[u64; 7]>; 3]>::new());
    for order in orders {
        let (mut i, mut f) = (ArrayVec::<[i64; 7]>::new(), ArrayVec::<[u64; 7]>::new());
        for &c in order {
            let (a, b) = split(c);
            i.push(a);
            f.push(b);
        }
        oi.push(i);
        of.push(f);
    }
    DoviReshapingCurve {
        num_pivots_minus2: 0,
        pivots: deltas(&[0, 1023]),
        mapping_idc: DoviMappingMethod::MMR,
        polynomial: None,
        mmr: Some(DoviMMRCurve {
            mmr_order_minus1: vec![(orders.len() - 1) as u8],
            mmr_constant_int: vec![ci],
            mmr_constant: vec![cf],
            mmr_coef_int: vec![oi],
            mmr_coef: vec![of],
        }),
    }
}

fn main() -> Result<()> {
    let out_path = std::env::args().nth(1).expect("usage: dolby-vision-fixture-rpu <out.bin>");
    let config: GenerateConfig = serde_json::from_str(CONFIG)?;
    let luma = [
        [[0.0, 1.1, -0.2], [0.02, 0.98, -0.05], [-0.04, 1.02, 0.02]],
        [[0.0, 1.05, -0.1], [0.01, 0.99, -0.03], [-0.02, 1.01, 0.01]],
    ];
    let mut out = Vec::new();
    for (index, mut rpu) in config.generate_rpu_list()?.into_iter().enumerate() {
        rpu.header.coefficient_log2_denom = DENOM;
        rpu.header.coefficient_log2_denom_length = DENOM as u32;
        let mapping = rpu.rpu_data_mapping.as_mut().expect("a profile 5 RPU maps");
        mapping.curves[0] = polynomial(&[0, 256, 640, 1023], &luma[index]);
        mapping.curves[1] = mmr(0.01, &[
            [0.02, 0.95, 0.01, -0.03, 0.02, 0.01, -0.01],
            [0.01, 0.04, 0.0, 0.0, 0.01, 0.0, 0.0],
            [0.0, -0.02, 0.0, 0.0, 0.0, 0.0, 0.0],
        ]);
        mapping.curves[2] = mmr(-0.01, &[
            [-0.02, 0.01, 1.02, 0.02, -0.03, 0.0, 0.01],
            [0.0, 0.0, -0.03, 0.01, 0.0, 0.0, 0.0],
            [0.0, 0.0, 0.02, 0.0, 0.0, 0.0, 0.0],
        ]);
        rpu.modified = true;
        // Annex B start code, then the NAL unit without the two-byte 0x7C01 header dovi_tool adds back.
        out.extend_from_slice(&[0, 0, 0, 1]);
        out.extend_from_slice(&rpu.write_hevc_unspec62_nalu()?[2..]);
    }
    std::fs::write(out_path, out)?;
    Ok(())
}
