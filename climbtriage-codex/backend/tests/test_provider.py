import base64

import cv2
import numpy as np
import pytest

from climbtriage.providers import FalSam3, ProviderContractError, mask_polygons, image_dimensions


def png(array):
    return cv2.imencode(".png", array)[1].tobytes()


def test_mask_parts_preserve_gap_between_disconnected_regions():
    mask = np.zeros((100, 200), np.uint8)
    mask[10:30, 20:40] = 255
    mask[50:70, 80:100] = 255
    parts = mask_polygons(png(mask), 200, 100)
    assert len(parts) == 2
    assert all(0 <= x <= 1 and 0 <= y <= 1 for part in parts for x, y in part)


def test_adapter_rejects_wrong_scale_and_colored_applied_mask():
    with pytest.raises(ProviderContractError, match="size"):
        mask_polygons(png(np.zeros((20, 20), np.uint8)), 30, 20)
    rgb = np.zeros((20, 20, 3), np.uint8)
    rgb[:, :, 1] = 255
    with pytest.raises(ProviderContractError, match="colored"):
        mask_polygons(png(rgb), 20, 20)


@pytest.mark.parametrize("url", ["http://127.0.0.1/a", "https://fal.media.evil.test/a", "https://queue.fal.run@evil.test/a"])
def test_provider_urls_do_not_allow_arbitrary_fetches(url):
    with pytest.raises(ProviderContractError):
        FalSam3.download_mask(url)


def test_binary_mask_data_uri_contract():
    raw = png(np.zeros((20, 20), np.uint8))
    assert FalSam3.download_mask("data:image/png;base64," + base64.b64encode(raw).decode()) == raw


def test_dimensions_are_checked_before_decoding():
    import struct
    malicious_header = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 100_000, 100_000)
    with pytest.raises(ProviderContractError, match="megapixels"):
        image_dimensions(malicious_header)
    jpeg = cv2.imencode(".jpg", np.zeros((60, 80, 3), np.uint8))[1].tobytes()
    assert image_dimensions(jpeg) == (80, 60)
